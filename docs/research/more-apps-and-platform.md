# More apps, the platform and the ecosystem

Second research round (2026-10-31), after [comparable-apps.md](comparable-apps.md). Ideas and lessons only, in our own words; no code copied. "Unverified" marks claims not confirmed from a primary source.

## More open-source AI apps

| App | Licence | Stars | What to learn |
|---|---|---|---|
| [PocketPal AI](https://github.com/a-ghorbani/pocketpal-ai) | MIT | ~8.9k | Hugging Face model search with a quantisation picker sized to the phone's RAM; a tokens/s benchmark; personas ("Pals"); on-device Kokoro voice. Lessons: a dependency bump silently dropped the NPU backend ([#858](https://github.com/a-ghorbani/pocketpal-ai/issues/858)); a wrong free-RAM estimate blocked voice ([#1040](https://github.com/a-ghorbani/pocketpal-ai/issues/1040)); F-Droid is the top request but blocked by the build ([#55](https://github.com/a-ghorbani/pocketpal-ai/issues/55)). |
| [Google AI Edge Gallery](https://github.com/google-ai-edge/gallery) | Apache-2.0 | ~25k | Same LiteRT-LM stack as Locus. Agent Skills (text, JavaScript in a hidden WebView, intents) from a list, a URL or a file; an experimental MCP client; a model allowlist with minimum RAM and default accelerator; on-device speech in and out. Lessons: secrets in backups ([#1325](https://github.com/google-ai-edge/gallery/issues/1325)) and in logcat ([#1324](https://github.com/google-ai-edge/gallery/issues/1324)); path traversal on skill import ([#949](https://github.com/google-ai-edge/gallery/issues/949)); GPU disabled by a device-name check ([#1460](https://github.com/google-ai-edge/gallery/issues/1460)). Top request: a local API server ([#552](https://github.com/google-ai-edge/gallery/issues/552)). |
| [ChatterUI](https://github.com/Vali-98/ChatterUI) | AGPL-3.0 | ~2.9k | Character cards; custom API templates; use a model file in place instead of copying it. Lessons: RTL text streamed backwards ([#579](https://github.com/Vali-98/ChatterUI/issues/579)), a crash on a Unicode quote ([#585](https://github.com/Vali-98/ChatterUI/issues/585)), auto-title broken by thinking text ([#586](https://github.com/Vali-98/ChatterUI/issues/586)). |
| [Maid](https://github.com/Mobile-Artificial-Intelligence/maid) | MIT | ~2.7k | On F-Droid with the NonFreeNet anti-feature: a precedent for an LLM client there. Publishes its signing-key fingerprints. |
| [SmolChat](https://github.com/shubham0204/SmolChat-Android) | Apache-2.0 | ~0.9k | Kotlin + Compose + llama.cpp over JNI, the closest stack to our planned GGUF support; per-model prompts and sampling; one-off "tasks". |
| [llama.cpp Android](https://github.com/ggml-org/llama.cpp/tree/master/examples/llama.android) | MIT | n/a | Arm's Kotlin binding: a token Flow, a GGUF metadata reader, runtime CPU-feature detection. The natural base for GGUF in Locus; the Hexagon NPU backend needs Qualcomm's proprietary SDK (not F-Droid-compatible). |
| [MLC LLM](https://github.com/mlc-ai/mlc-llm) | Apache-2.0 | ~23k | Android build broken and the demo app stale ([#3552](https://github.com/mlc-ai/mlc-llm/issues/3552)); not a good backend today. |
| [Off Grid AI](https://github.com/off-grid-ai/OGAM) | MIT | ~3.3k | On-device RAG over PDFs (MiniLM embeddings in SQLite); a RAM view with per-model eject; a download queue; draft-then-approve actions. Lesson: it fell back to CPU while still showing "GPU" ([#373](https://github.com/off-grid-ai/OGAM/issues/373)). |
| [Kelivo](https://github.com/Chevey339/kelivo) | AGPL-3.0 | ~4.4k | The closest feature match: proot workspaces, local MCP servers inside the sandbox, skill import from text/zip/GitHub, an ask-user tool, key pools and per-provider proxies, backups with snapshots before restore, and "context logs" showing exactly what was sent. Lessons: 32-bit phones couldn't install Linux ([#1231](https://github.com/Chevey339/kelivo/issues/1231)); backup and restore failures ([#1276](https://github.com/Chevey339/kelivo/issues/1276)). |

Also seen: OpenClaw-on-Termux setups (pin what you install); opendroid (controls other apps, which conflicts with our principles, but its LiteRT model manager is a good pattern); local-dream (CC BY-NC, incompatible with AGPL); [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) (Apache-2.0, the usual on-device speech engine).

## Platform (Android 16 and 17)

- **Local network permission (Android 17, target 37):** connections to private addresses need `ACCESS_LOCAL_NETWORK`, which Locus didn't declare. Ollama, LM Studio, LAN SSH and LAN MCP would be blocked. [Guide](https://developer.android.com/privacy-and-security/local-network-permission). Being fixed.
- **Background network (Android 15):** requests outside a valid process lifecycle fail, usually as "Unable to resolve host" (we hit this with ChatGPT sign-in). Make sure a foreground service or job is running before any background request.
- **Job quotas (Android 16)** apply even under a foreground service; read `WorkInfo.getStopReason()` for diagnostics.
- **Live Updates** need `POST_PROMOTED_NOTIFICATIONS` and fit a running agent, not task results. [Guide](https://developer.android.com/develop/ui/views/notifications/live-update).
- **AppFunctions** (alpha): exposing Locus's actions fits our principles; calling other apps' functions is a separate decision.
- **Gemini Nano Prompt API** (beta): foreground only, closed source, and not available on every flagship. Watch.
- **Large screens:** apps can no longer lock orientation or resizing on 600dp+ screens at target 37; state must survive re-creation.
- **Android 17 also:** background audio needs a foreground service (read-aloud with the screen off); memory limits by RAM; Android 18 will require `FLAG_GRANT_READ_URI_PERMISSION` when sharing.

## Model APIs (since mid-2026)

- **Anthropic:** prompt caching must be requested (`cache_control`); Locus never requested it, so Anthropic chats weren't cached. Being fixed. Also: newer models missing from our server-compaction list; a server-side task budget (beta); mid-conversation system messages and per-message effort, which change context without breaking the cache; cache-miss diagnostics; a larger request size limit. Skip the hosted MCP connector and memory tool (they'd move tokens or data to the provider).
- **OpenAI:** server-side compaction in Responses works with `store:false` and returns an encrypted item we can replay; explicit cache controls on newer models; spend-limit 429s must not be retried. Being fixed.
- **Gemini:** build the deferred native adapter on the Interactions API in stateless mode. Unverified: whether new paid projects store requests by default on the OpenAI-compatible endpoint.

## MCP

No spec revision after 2026-07-28, which made the protocol stateless; the Kotlin SDK only has experimental support so far. Client ID Metadata Documents (a static JSON file, hostable on GitHub Pages) replace dynamic registration. Roots, Sampling and Logging are deprecated; elicitation arrives as `input_required`, which can use the ask-user card. MCP Apps (server-provided HTML UIs) are supported by 12 of 16 tracked clients.

## F-Droid

What Locus would need:
1. Build proot and talloc from source in the F-Droid recipe and remove the checked-in `.so` files.
2. `dependenciesInfo { includeInApk = false; includeInBundle = false }`.
3. Version code and name in source (not only from CI).
4. Make the Alpine download an explicit opt-in with an equally easy Skip, and say it bypasses F-Droid's checks.
5. LiteRT-LM ships a prebuilt native library in its AAR; whether F-Droid accepts that is unverified (fallback: a flavour without it).
6. Reproducible builds signed with our own key; fastlane metadata; a merge request to fdroiddata.
7. Declare the NonFreeNet anti-feature (as Maid does); keep any update check off by default.
