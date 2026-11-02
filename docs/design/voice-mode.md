# Voice mode: design

Status: v1 shipped (Unreleased), *not yet tried on the phone*. Roadmap: "Voice mode" under Product and platform; research: [product C2](../research/product-platform-2026.md#c-competitor-features).

v1 is a hands-free loop on Android's own speech services: the system speech recognizer, the normal agent in the current chat, and the system text-to-speech engine. It adds no network service and no library.

Code:
- `feature/chat/.../voice/VoiceSession.kt`: the loop as a pure, single-threaded state machine (listening, sending, working, speaking, approvals, barge-in, pauses, errors), with `BargeIn` and `hearError`.
- `feature/chat/.../voice/VoiceApprovals.kt`: which approvals voice may answer, what is said, and how answers are parsed.
- `feature/chat/.../voice/AndroidVoice.kt`: the Android adapters `SystemEars` (SpeechRecognizer), `SystemMouth` (TextToSpeech) and `VoiceFocus` (audio focus).
- `feature/chat/.../voice/VoiceModeScreen.kt`: the full-screen UI, the microphone permission, and `voiceView` (chat state to loop input).
- `SpeechText.forVoice` (feature/chat): the reply as voice mode says it.
- `ApprovalFlag` and `ApprovalRequest.flags` (core/agent `AgentLoop.kt`): why a call asks, set where approvals are made.
- `VoiceSettings` (core/model), Settings → Voice (`feature/settings/.../VoiceSettingsPage.kt`).
- Ways in: the mic's long-press and "Voice mode" in the composer's + sheet (`LocusComposer.kt`); the Voice launcher shortcut opens a new chat with `ChatRoute(voice = true)` (`MainActivity.kt`).

Tests: `VoiceSessionTest`, `VoiceApprovalsTest`, `VoiceViewTest`, `SpeechTextTest` (feature/chat); `AgentLoopTest` and `TaintTest` check the flags (core/agent).

## Research (read 2026-11-02)

Each item says what the source says and what Locus does with it. Quotes are from the linked pages.

### SpeechRecognizer

- [`SpeechRecognizer`](https://developer.android.com/reference/android/speech/SpeechRecognizer): "This class's methods must be invoked only from the main application thread." "The caller MUST invoke destroy() … when it is no longer needed." "The implementation of this API is likely to stream audio to remote servers to perform speech recognition. As such this API is not intended to be used for continuous recognition, which would consume a significant amount of battery and bandwidth." "The application must have Manifest.permission.RECORD_AUDIO permission to use this class."
- `createOnDeviceSpeechRecognizer(Context)` and `isOnDeviceRecognitionAvailable(Context)` were added in API 31. "If this method returns false, SpeechRecognizer.createOnDeviceSpeechRecognizer(Context) will fail." `checkRecognitionSupport` and `triggerModelDownload` (API 33) can ask about and fetch on-device language models.
- Error codes: `ERROR_SPEECH_TIMEOUT` ("No speech input"), `ERROR_NO_MATCH`, `ERROR_RECOGNIZER_BUSY`, `ERROR_INSUFFICIENT_PERMISSIONS`, the network errors, and on API 31+ `ERROR_LANGUAGE_NOT_SUPPORTED` ("not available to be used with the current recognizer"), `ERROR_LANGUAGE_UNAVAILABLE` ("supported, but not available currently (e.g. not downloaded yet)") and `ERROR_SERVER_DISCONNECTED`.
- [`RecognizerIntent.EXTRA_PREFER_OFFLINE`](https://developer.android.com/reference/android/speech/RecognizerIntent) (API 23): "indicate whether to only use an offline speech recognition engine … Depending on the recognizer implementation, these values may have no effect." `EXTRA_PARTIAL_RESULTS`: "The server may ignore a request for partial results in some or all cases." `EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS`: "it is extremely rare you'd want to specify this value", generally only with `EXTRA_SEGMENTED_SESSION` (API 33).
- [`RecognitionListener`](https://developer.android.com/reference/android/speech/RecognitionListener): `onPartialResults` "may be called zero, one or multiple times"; `onRmsChanged`: "There is no guarantee that this method will be called."

Locus: one recognition session per turn, never a continuous stream. It uses the on-device recognizer on API 31+ when `isOnDeviceRecognitionAvailable` says there is one. Otherwise it uses the system recognizer with `EXTRA_PREFER_OFFLINE`. If the on-device one reports a language error, Locus falls back to the system recognizer. If offline-only fails with a language or network error, Locus drops the offline request. Each fallback changes the engine line on screen. Partial results and the level are shown when the recognizer sends them; the screen works without either. Locus doesn't set the silence length and lets the recognizer end the turn. `triggerModelDownload` is left for later (see below).

### TextToSpeech

- [`UtteranceProgressListener`](https://developer.android.com/reference/android/speech/tts/UtteranceProgressListener): "The callbacks specified in this method can be called from multiple threads." `onDone`: "All audio will have been played back by this point for audible output … guaranteed to be called after onStart". `onStop(utteranceId, interrupted)` (API 23) is "called when an utterance has been stopped while in progress or flushed from the synthesis queue … if a client calls TextToSpeech.stop() or uses TextToSpeech.QUEUE_FLUSH". `onError(String, int)` replaces the deprecated one-argument form.
- [`TextToSpeech`](https://developer.android.com/reference/android/speech/tts/TextToSpeech): `setAudioAttributes` (API 21), `setSpeechRate` ("1.0 is the normal speech rate"), and `getVoices` / `setVoice` (API 21).

Locus: `SystemMouth` posts every callback to the main thread. It splits long text into chunks under `getMaxSpeechInputLength` and treats the last chunk's `onDone` as "finished speaking". A stop (`onStop`) ends nothing by itself, because the loop already knows it stopped the voice. Speech uses `USAGE_ASSISTANT` and `CONTENT_TYPE_SPEECH`. The speed comes from Settings → Voice and also applies to read-aloud. The voice and engine are Android's own Text-to-speech settings, which Settings → Voice opens. Locus doesn't keep its own voice list.

### Audio focus

- [Manage audio focus](https://developer.android.com/media/optimize/audio-focus): "Call requestAudioFocus() immediately before starting to play". `AUDIOFOCUS_GAIN_TRANSIENT` is for when "you expect to play audio for only a short time and you expect the previous holder to pause". On a transient loss "your app should duck … or pause playing but otherwise maintain the same state … be prepared to resume". On a permanent `AUDIOFOCUS_LOSS`, "To restart playback, the user must take an explicit action". Delayed focus gain: "a request made when the focus is locked returns AUDIOFOCUS_REQUEST_DELAYED. When the condition … no longer exists, such as when a phone call ends, the system grants the pending focus request". Automatic ducking "isn't useful when playing spoken content … the app should pause instead". Android 15: an app "cannot request audio focus unless it's the top app or running a foreground service".
- [Android 17 background audio hardening](https://developer.android.com/about/versions/17/changes/bg-audio): playback, focus requests and volume changes from the background "must have a visible activity or must be running a foreground service". Apps targeting 37 also need a foreground service with while-in-use capabilities. Restricted focus requests return `AUDIOFOCUS_REQUEST_FAILED`.

Locus: one `AUDIOFOCUS_GAIN_TRANSIENT` request for the whole session, with delayed gain. Other apps' music pauses while you talk with Locus. A call (transient loss, or a delayed grant) pauses voice mode, which goes on by itself when the call ends; an interrupted reply is read again from the start. A permanent loss waits for a tap. "Can duck" losses (notification sounds) are ignored. A recognizer may take focus itself as it starts listening, so a transient loss within two seconds of starting to listen is ignored. A call later in the turn ends recognition with an error, and the loop stops until a tap. Voice mode runs only while its screen is visible, so the Android 15 and 17 rules are met without a service.

### Android 17 assistant audio

- [Android 17 features](https://developer.android.com/about/versions/17/features), "Dedicated Assistant volume stream": "Android 17 introduces a dedicated Assistant volume stream for Assistant apps, for playback with USAGE_ASSISTANT … Assistant apps with access to the new MODE_ASSISTANT_CONVERSATION audio mode can further improve the volume control consistency."
- [`AudioAttributes.USAGE_ASSISTANT`](https://developer.android.com/reference/android/media/AudioAttributes) has been in the platform since API 26: "for audio responses to user queries, audio instructions or help utterances".
- [`AudioManager.MODE_ASSISTANT_CONVERSATION`](https://developer.android.com/reference/android/media/AudioManager) (added in 36.1): "should only be used by applications that introduce an interactive assistant communication … the calling app must be allowed to manage assistant audio (see canManageAssistantAudio())". `canManageAssistantAudio()` (37.2) says: "To request access, use Settings.ACTION_MANAGE_ASSISTANT_AUDIO."

Locus: TTS and the focus request use `USAGE_ASSISTANT` on every version. On Android 17 voice mode is on the Assistant volume, so you can mute media and still hear Locus. Locus doesn't set `MODE_ASSISTANT_CONVERSATION` in v1. It needs a special access the user grants in Settings, `canManageAssistantAudio` is newer than Locus's compile SDK, and the mode changes routing for the whole phone. Revisit it with the assistant role ([product P5](../research/product-platform-2026.md#p-android-platform)).

### Microphone and foreground services

- [Foreground service types](https://developer.android.com/develop/background-work/services/fgs/service-types): `microphone` needs `FOREGROUND_SERVICE_MICROPHONE` and the `RECORD_AUDIO` runtime permission. "The RECORD_AUDIO runtime permission is subject to while-in-use restrictions. For this reason, you cannot create a microphone foreground service while your app is in the background".
- [While-in-use restrictions](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start) (Android 14+): "if your foreground service needs a while-in-use permission, you must call Context.startForegroundService() or Context.bindService() while your app has a visible activity". The exemptions include a service started by "an app which provides the VoiceInteractionService".

Locus: v1 adds **no microphone foreground service**. It listens only while the voice screen is in front. Leaving it (ON_STOP) pauses voice mode and the microphone, and a tap goes on. The agent keeps running in the existing `specialUse` AgentService as usual. `RECORD_AUDIO` is declared in feature/chat's manifest and asked for the first time voice mode opens, with an explanation and an App info link if it was refused. Dictation in the composer still uses the system recognizer's own activity, which needs no permission. Hands-free listening with the screen off would need a `microphone` service started from the visible screen. That waits for the assistant role, whose `VoiceInteractionService` is the documented exemption.

### Barge-in

- [Pipecat interruptions](https://docs.pipecat.ai/pipecat/fundamentals/interruptions): when the user speaks over the bot, "Each processor cancels its processing task", TTS "stops, and the service clears its text buffers", and the transport drains unplayed audio. "Only the words actually spoken are committed to the assistant context." It suggests `MinWordsUserTurnStartStrategy(min_words=3)` "so brief utterances like 'okay' or 'yeah' don't interrupt the bot" ([interruption strategies](https://docs.pipecat.ai/server/utilities/turn-management/interruption-strategies)). The interruption-strategy API itself is deprecated in favour of user-turn strategies.
- [OpenAI Realtime VAD](https://developers.openai.com/api/docs/guides/realtime-vad): server VAD "automatically chunks the audio based on periods of silence"; semantic VAD "uses a semantic classifier to detect when the user has finished speaking"; `interrupt_response` and `create_response` are "only used in speech-to-speech conversations".

Locus: barge-in by tap always works: tapping the circle while Locus talks stops the voice and listens. Barge-in by voice (Settings → Voice, on by default) keeps one recognition session open while Locus talks. It interrupts only when the recognizer hears at least two words that are not in what Locus is saying (`BargeIn`). This min-words rule doubles as an echo guard: on a speaker the recognizer hears Locus's own voice, and those words are ignored. A side effect matters for safety: the "allow" in the approval prompt can never be picked up as an answer. The chat transcript already holds the full reply, so nothing is truncated. Only the speech stops.

## The loop

`VoiceSession` is pure. `SystemEars`, `SystemMouth` and `VoiceFocus` call it on the main thread, and tests drive it with fakes and a fake clock.

1. **Listen.** The microphone is on only in the Listening phase, and only then does the screen show "Microphone on". The live level (from `onRmsChanged`) scales the circle, and the partial transcript shows under it.
2. **Send.** What was heard goes through the chat's own `send`, the same call the composer makes. It uses the same model, tools, approvals, budget, taint and prompt cache, and the message appears in the chat. Send warnings (the model can't take it) and heat holds stop voice mode with "Check the chat"; Android 17's local-network prompt runs as for typing. If no run starts within 10 s, voice mode says so.
3. **Work.** The circle breathes and the title says what the agent is doing (`toolActivity`). With spoken status on, the first tool is said ("Working on it. Running a command.") and later changes at most every 15 s. The mic stays off while the agent works. `SpeechRecognizer` isn't meant for continuous listening, and an open mic during a long task costs battery and privacy. Tapping the circle opens it for one turn.
4. **Mid-task notes.** Anything said while the agent works goes through the same `send`. The runner queues it for the next step boundary, like typing in the composer (#55). Voice mode says "Got it. Locus will read that after this step." An open `ask_user` question is read aloud, with its options, and the next thing said answers it.
5. **Speak.** When the run ends, the chat's latest reply is spoken by `SpeechText.forVoice`. It uses read-aloud's filter (no Markdown, thinking, links spelled out, code or tables), drops the "Code block." and "Table." markers, and stops near 900 characters at a sentence end with "The rest is on the screen." Narration between steps and tool calls are separate chat items and are never spoken. A run that ends with an error says the error. If the reply doesn't reach the chat within 4 s of the run ending, voice mode says so.
6. **Again.** With "Listen after each reply" on (the default), it listens again. Otherwise it waits for a tap.

## Approvals by voice

The approval card stays on the voice screen with Allow and Deny buttons, so a tap always works.

Voice may answer only a **plain Ask-level request**: one whose tool is set to Ask, with no other reason to ask. `AgentLoop` now records why a call asks, as `ApprovalRequest.flags`:

| Flag | Set when | Voice |
|---|---|---|
| (none) | The tool's policy is Ask | Allowed |
| `TAINTED` | The run read outside content and the call could carry data out or persist it ([taint](taint.md)) | Tap only |
| `MUST_CONFIRM` | The tool demands confirmation for these arguments: payment-like clicks, form submits, large uploads | Tap only |
| `SKILL_SCOPE` | Outside a loaded skill's manifest ([skill manifests](skill-manifests.md)) | Tap only |
| `ESCALATED` | A workflow approval point, or a helper's parent limits | Tap only |
| `reviewerNote` set | The safety reviewer looked and didn't clear it | Tap only |
| flags `null` | A request not built by `AgentLoop` (unknown) | Tap only |

For a request voice may answer, it says "Locus wants to: Run ls -la. Say allow, or deny." Saying "allow" (or yes, okay, go ahead) doesn't approve. Locus asks "To allow it, say confirm. Or say no.", and only "confirm" approves. "Deny", "no", "don't allow" or "cancel" deny at once, with no confirmation, because denying is safe. A negation always wins ("don't allow" denies), and "yes no" is unclear. Answers over six words are conversation, not answers. Two unclear answers in a row hand the approval to the screen.

A tap-only request is read with its reason ("Locus read outside content during this task, so this needs a tap. Tap Allow or Deny on the screen."). Nothing said changes it, and the card shows the reason in caution colour. An approval answered on screen while voice mode asks about it cancels the spoken prompt.

## Exiting and pausing

- **End voice mode**: the big button at the bottom, the close icon or Back. The agent's task goes on in the chat.
- **Silence**: 30 s of listening with nothing heard, counted from the last thing said or the last reply, ends voice mode. It never ends while the agent works.
- **Audio focus**: see above. A call pauses, and voice mode goes on when the call ends.
- **Background**: leaving the screen pauses; a tap goes on.
- **Errors**: "busy" retries once. Missing permission, network-only recognizers, missing languages and a TTS engine that can't speak stop the loop with the reason. A tap tries again.

## Privacy

- On-device recognition is preferred: the on-device recognizer when the phone has one, otherwise the system recognizer asked to stay offline. The screen always names the engine. "On-device recognition" says speech becomes text on the phone. For the system recognizer it gives the recognizer app's name and says it may use its own service.
- No audio is stored. Locus never records. The recognizer holds the microphone and Locus receives only text, which goes into the chat like typed text.
- The microphone is on only in the Listening phase, shown by "Microphone on" (and by Android's own privacy indicator). Nothing listens in the background.
- `RECORD_AUDIO` is asked for the first time voice mode opens.

## Settings → Voice

- **Speed** (0.5×–2×), for read-aloud and voice mode.
- **Voice and speech engine** opens Android's Text-to-speech settings.
- **Listen after each reply** (on).
- **Say what it's doing** (on): spoken status.
- **Interrupt by speaking** (on). Turn it off if a speaker setup keeps hearing itself.

## Later (out of scope for v1)

### v2: OpenAI Realtime and Gemini Live with your own key

- [OpenAI Realtime](https://developers.openai.com/api/docs/guides/realtime): "The session connects over WebRTC in the browser or WebSocket on the server." The model named is `gpt-realtime-2.1`. "Use POST /v1/realtime/client_secrets to create ephemeral credentials for browser or mobile clients." Sessions take function tools and MCP servers and handle "audio turns, tools, interruptions, and handoffs inside that session"; turn detection is server or semantic VAD (above).
- [Gemini Live API](https://ai.google.dev/gemini-api/docs/live-api): "a stateful WebSocket connection (WSS)". Input is 16-bit PCM at 16 kHz, output is 24 kHz. It supports function calling, and "users can interrupt the model at any time". For client-side connections it recommends ephemeral tokens over API keys.

Plan: a `RealtimeVoice` adapter per provider behind the same `VoiceSession` shape, with the provider's VAD replacing `SystemEars` and its audio replacing `SystemMouth`. Locus is the "server" and holds the BYOK key on the phone. It mints an ephemeral client secret per session where the provider offers one, so the long-lived key never sits in a socket URL. The hard part is the tools. Each realtime function call must go through Locus's own `AgentLoop` authorisation (policy, taint, reviewer, approvals, budgets, the audit log), not a parallel path. Realtime sessions also bill audio tokens at a separate rate, which the budget meter must count. That is the L in "M, then L".

### On-device speech: sherpa-onnx

- [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) (Apache-2.0) runs "without Internet connection". It does streaming and non-streaming ASR, Silero VAD and TTS on Android, with Kotlin and Java APIs.

Plan (roadmap "On-device speech"): swap `SystemEars` for sherpa-onnx streaming ASR with Silero VAD, which also gives true echo-aware barge-in from a local VAD instead of recognizer partials. Swap `SystemMouth` for a local TTS model (Kokoro or Piper). The loop, approvals and tests don't change. Models are downloads of tens to hundreds of MB, handled like on-device LLMs (Wi-Fi only by default, `ModelDownloadWorker`).

### Also later

- Hands-free with the screen off: a `microphone` foreground service started from the visible voice screen, or the assistant role's `VoiceInteractionService` exemption. It comes with "Default assistant" (product P5).
- `checkRecognitionSupport` and `triggerModelDownload` (API 33), to offer downloading the on-device language model instead of falling back.
- `MODE_ASSISTANT_CONVERSATION` once Locus can ask for assistant-audio access.

## Phone checklist

- [ ] Long-press the mic in a chat and use + → Voice mode: both open voice mode. The Voice launcher shortcut opens a new chat in voice mode.
- [ ] First open asks for the microphone; "Not now" closes; refusing then shows "Allow in settings".
- [ ] The engine line names on-device recognition on the S25 (Android 17).
- [ ] Ask a question: partial text appears, the circle follows your voice, the reply is spoken without code or tables, and it listens again.
- [ ] Interrupt a long reply by tapping, then by speaking two or more words. Locus's own voice on the speaker doesn't interrupt it.
- [ ] Start a task with commands: spoken status. Tap and add a note mid-task: "Got it…", and the chat shows it as "Told while working".
- [ ] A command set to Ask: "allow" then "confirm" approves, and "deny" denies. In a task that read a web page, a sending call says it needs a tap and voice can't approve it.
- [ ] Call the phone during a reply: voice mode pauses and goes on after the call.
- [ ] Press Home: it pauses, and the mic indicator goes off. Stay silent for 30 s: voice mode ends.
- [ ] Settings → Voice: speed changes read-aloud and voice mode. On Android 17 the volume keys during a reply change the Assistant volume.
