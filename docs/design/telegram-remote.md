# Telegram remote channel: approvals by button, and remote mode

Status: shipped (Unreleased), *not yet tried against the live Bot API or on the phone*. Roadmap: Phase 2c "Telegram remote channel" and Phase 3 "Inbound Telegram" ([ROADMAP](../ROADMAP.md)). Builds on the outbound channel (#19: `notify_me` and scheduled results to the user's own bot).

Code:
- `core/agent/.../integrations/TelegramRemote.kt`: updates, callback data and nonces, `ApprovalDesk`, the inbound rules, `RateLimit`, and `Channel` (offer, withdraw, poll), all without Android.
- `core/agent/.../AgentLoop.kt`: `ApprovalRequest.inAppOnly` and `inAppOnlyReason`, derived from `ApprovalRequest.flags` (the rule voice mode uses too, docs/design/voice-mode.md).
- `core/data/.../integrations/TelegramRemoteHub.kt` (runs it in the app), `TelegramRemoteService.kt` (remote mode's foreground service), `TelegramSetup.kt` (the settings).
- `core/data/.../agent/AgentRunner.kt`: `sendRemote`, and the Telegram chat's read-only rules in `run`. `MessageQueue`: a queued message keeps its note.
- `core/model`: `TelegramRemoteSettings` (sealed with the permissions in `PolicySeal`), `ModeNote.telegram()`.
- `core/ui/.../TelegramSheet.kt`: the Remote section.
- Tests: `TelegramRemoteTest` (core/agent), `TelegramRemoteSealTest` (core/model), `MessageQueueTest` (core/data).

## Research (read 2026-11-02)

### Getting updates without a server

- [Bot API, Getting updates](https://core.telegram.org/bots/api#getting-updates): "There are two mutually exclusive ways of receiving updates for your bot", `getUpdates` and webhooks; "You will not be able to receive updates using getUpdates for as long as an outgoing webhook is set up."
- [setWebhook](https://core.telegram.org/bots/api#setwebhook): `url` is required, an "HTTPS URL to send updates to", and "Ports currently supported for webhooks: 443, 80, 88, 8443." A phone behind carrier NAT has no reachable HTTPS endpoint, and a relay would be a server Locus doesn't have. **Local-first means polling.**
- [getUpdates](https://core.telegram.org/bots/api#getupdates):
  - `offset` "Must be greater by one than the highest among the identifiers of previously received updates." "An update is considered confirmed as soon as getUpdates is called with an offset higher than its update_id." A negative offset reads from the end of the queue, and "All previous updates will be forgotten."
  - `timeout`: "Timeout in seconds for long polling. Defaults to 0, i.e. usual short polling." `limit` 1–100.
  - `allowed_updates`: "A JSON-serialized list of the update types you want your bot to receive."
  - Unconfirmed updates "will not be kept longer than 24 hours."
- [Update](https://core.telegram.org/bots/api#update): "Update identifiers start from a certain positive number and increase sequentially"; after a week without updates the next one may be random. [Message](https://core.telegram.org/bots/api#message) `date` is "Date the message was sent in Unix time". [User](https://core.telegram.org/bots/api#user) `id` is a 64-bit integer.

Locus: one long poll at a time with `timeout` 25 s, `limit` 100 and `allowed_updates` `["message","callback_query"]`. The offset is one past the highest `update_id` seen and is moved **before** the updates are handled, so a crash can't run one twice. The first poll after a start has no offset: anything Telegram still holds is read and confirmed, but old messages fail the time check below and old button presses fail the nonce check. The OkHttp read timeout is 10 minutes, well above 25 s.

### Buttons

- [InlineKeyboardMarkup](https://core.telegram.org/bots/api#inlinekeyboardmarkup): "an inline keyboard that appears right next to the message it belongs to." [InlineKeyboardButton](https://core.telegram.org/bots/api#inlinekeyboardbutton) `callback_data`: "Data to be sent in a callback query to the bot when the button is pressed, 1-64 bytes".
- [CallbackQuery](https://core.telegram.org/bots/api#callbackquery): `id`, `from` ("Sender"), `message` ("Message sent by the bot with the callback button that originated the query"), `chat_instance`, `data`. "After the user presses a callback button, Telegram clients will display a progress bar until you call answerCallbackQuery."
- [answerCallbackQuery](https://core.telegram.org/bots/api#answercallbackquery): "send answers to callback queries sent from inline keyboards"; `text` is "0-200 characters", `show_alert` shows an alert instead of a notification, `cache_time` defaults to 0.
- [editMessageText](https://core.telegram.org/bots/api#editmessagetext) edits a sent message; sent without `reply_markup`, the inline keyboard goes away.

Locus: callback data is `lc1:<nonce>:a|d` (28 bytes), the nonce 128 random bits from `SecureRandom` in base64url. Every press is answered (a short toast, or nothing for a stranger), so no spinner hangs. When an approval ends, its message is rewritten without buttons and with how it ended.

### Android: when to poll

- [WorkManager, define work](https://developer.android.com/develop/background-work/background-tasks/persistent/getting-started/define-work): "The minimum repeat interval that can be defined is 15 minutes (same as the JobScheduler API)." Far too slow for an approval or a chat.
- [Doze and App Standby](https://developer.android.com/training/monitoring-device-state/doze-standby): Doze "Suspends network access"; the page says FCM's shared connection exists because separate persistent connections "can deplete the battery rapidly", and "Don't start a foreground service just to prevent the system from determining that your app is idle." Locus has no server to send FCM, so a long poll is the only way, and it should run only when the user is waiting on it. (See also [remote execution](remote-execution.md) on Doze and foreground services.)
- [Foreground service types](https://developer.android.com/develop/background-work/services/fgs/service-types): `specialUse` "Covers any valid foreground service use cases that aren't covered by the other foreground service types", with a `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` explanation reviewed by Play.

Locus polls in exactly two situations, and in both a foreground service is already running:

1. **An approval with buttons is waiting.** A task that waits for approval runs under AgentService, so polling adds no new process or wakeup; it stops as soon as no button is waiting.
2. **Remote mode is on.** The user turns it on for a set time (30 min to 8 h). `TelegramRemoteService` (`specialUse`) shows a persistent notification ("Telegram remote mode is on · Until 14:30") with Turn off. The sheet says it keeps a connection open and uses some battery.

Errors back off from 2 s to 2 min; a refused token (401/404) stops polling. A 409 (a webhook, or another getUpdates on the same bot) backs off too.

## Approvals by button

When approvals from Telegram are on, Locus is in the background (not on screen), and a task asks for approval, the request goes to the bot chat with **Deny** and **Allow**. It's also on the in-app card and the notification as before; whichever answers first wins, and the other is withdrawn.

A press counts only if all of these hold (`ApprovalDesk.press`):

- It came from the configured chat **and** the configured user. Remote features need a private chat with the bot, whose id is the user's own; a group (negative id) never qualifies, since anyone in it could press.
- Its data parses exactly as Locus's (`lc1:` + a 22-character nonce + `a`/`d`).
- The nonce belongs to an approval still in the chat. It is taken by the press, so a second press (or a replay) finds nothing. The approval ending in Locus (answered there, timed out after 30 minutes, the task stopped) or approvals being switched off also removes it.

### Never by button

Some approvals must be a person looking at Locus. `ApprovalRequest.inAppOnly` is derived from the request's `flags` and reviewer note, the same rule voice mode uses; it is set when any of these is true (unknown flags count too):

- **The safety reviewer flagged it** (it asked the user instead of allowing).
- **The call must be confirmed**: the tool's own `mustConfirm` (payments in the browser, sending email, deploys, deleting DNS records…), the run's taint (it read outside content and the call could carry data out or persist), a loaded skill's manifest, or a workflow's approval point.

Those go to Telegram without buttons, saying "Open Locus to approve. This one can't be approved from Telegram" and why. A press on one (forged data, say) gets "Open Locus to approve this one." A task started from Telegram is tainted, so anything outward it wants is always in-app only.

Approving from Telegram skips the phone's lock screen (a notification's Allow requires unlocking). That's the point of the feature, and why only plain Ask approvals qualify.

## Remote mode (inbound)

Off by default. While it's on, a text message to the bot from the configured chat and user:

- starts a task in a chat called **Telegram** (made on the first message and kept), or
- if that chat's task is running, joins it at the next step boundary as a mid-task note (steer and queue);
- and the task's answer, or its error, goes back to the bot chat when it ends.

Rules (`TelegramRemote.inbound`):

- **Only the owner.** Anything else is dropped without a reply (a reply would tell a stranger the bot is live).
- **Only after switching on.** A message dated before remote mode was switched on is ignored. Telegram keeps unconfirmed updates for up to 24 hours, so without this, turning remote mode on could run a message sent yesterday.
- **Rate-limited.** At most 6 messages a minute; the rest are dropped, with one "too many messages" reply per minute so a flood can't make Locus flood back.
- **At most 4,000 characters** of a message are used.
- **`/off`** switches remote mode off from anywhere, whatever the rate.
- **Auto-off.** Remote mode ends at its time limit (at most 8 hours), from the Turn off button, from `/off`, or when Locus's process ends (it is switched off at the next start, since its service died with the process). The bot says when it switched itself off.

### What a Telegram message may do

A bot chat isn't proof of the user: a stolen Telegram session, a shared device or a logged-in desktop can all send. So:

- **Tainted as external input.** Every message carries `ModeNote.telegram()`, the #104 `ModeNote.external` mechanism with Telegram as the source. `Taint.sourcesIn` adds "another app (Telegram)", `trustedUserMessages` leaves it out (its links aren't trusted hosts and the reviewer doesn't take it as the user's request), and compaction keeps it marked. A message queued mid-task keeps its note too (`QueuedMessage.note`), including across a restart.
- **Read-only by default.** The Telegram chat runs under a scheduled run's rules (`AgentConfig.readOnly`): only observing tools run, nothing that would ask is offered, no reviewer, the background step limit, and a budget that stops rather than asks. Unlike a scheduled run it can still be steered. This applies to that chat whoever types in it, so it can't be widened by moving a Telegram task elsewhere.
- **"Let Telegram tasks do more than read"** (off by default, with a warning) runs the chat as any chat, with approvals. Since the run is tainted, outward and persistent calls stay in-app only, but plain approvals (a workspace file write, say) can then be answered from the same Telegram chat that asked.

## Security notes

- **The token** stays in the vault (`TOKEN CHAT_ID`), is read for each call, and is part of the request path only. Every error is passed through `Telegram.scrub`. It's never logged or put in a prompt; the model never sees the remote settings at all.
- **The settings are sealed** with the permissions (`PolicySeal`): a Linux program editing the settings file can't turn on approvals or remote mode, widen it, or extend its time limit; a broken seal switches all three off. The chat id alone grants nothing.
- **Telegram content is untrusted**: update fields are parsed defensively (wrong types become `Other` and are only skipped), callback data must match exactly, and message text goes to the model only as tainted input.
- Removing the bot in the sheet switches the remote options off too.

## Not done

- Live tests against the Bot API, and phone checks of remote mode under Doze (see the PR checklist).
- Answering `ask_user` questions from Telegram, and the budget pause's Continue / Stop.
- Groups: remote features need a private chat by design.
