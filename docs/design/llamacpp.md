# GGUF models with llama.cpp

Roadmap item: "More local models: llama.cpp for GGUF models" (Phase 4, "Fully local models").
Locus already runs Gemma 4 on the phone through LiteRT-LM (`core/ondevice`). This adds any GGUF file
the user brings, run by llama.cpp, as a second kind of on-device model behind the same `ON_DEVICE`
provider. Sources were read on 2026-11-03.

## Decision

- **llama.cpp built from source, pinned to tag `b12066`** (commit `713319e2`, 2026-11-03, MIT), by
  `tools/build-llama.sh`. It follows the proot pattern (#161): the script builds the libraries, they
  are committed in `core/ondevice/src/main/jniLibs/<abi>/`, the **Native libs** workflow rebuilds
  them on a clean Linux runner and fails if they differ, and the F-Droid recipe deletes and rebuilds
  them.
- **Our own JNI bridge** (`core/ondevice/src/main/cpp/locus_llama.cpp`, about 500 lines), not an
  existing Android binding. The bridge uses llama.cpp's `common/chat.h`, the code llama-server runs,
  for chat templates, tool-call grammars and reply parsing.
- **CPU only in v1**, with ggml's runtime-selected CPU variants for arm64 (`GGML_CPU_ALL_VARIANTS`).
  No Vulkan or OpenCL yet, and no KleidiAI.
- **Four ways to get a model** ([Getting models](#getting-models)): a recommended list pinned to
  exact files, Hugging Face search, a pasted URL, and the system file picker. Every file ends up in
  app storage, checked the same way.
- **Tool calling when the model's template declares tools.** Otherwise the model is marked
  **chat only** (`tools = false`), and capability negotiation treats it as a model without tool calling.

## How to embed llama.cpp

### Options

| Option | Licence | Tool calling | F-Droid | Notes |
|---|---|---|---|---|
| llama.cpp's own Android sample, `examples/llama.android/lib` ("Arm AI Chat", the "Arm's Kotlin binding" in the roadmap) | MIT (llama.cpp's [LICENSE](https://github.com/ggml-org/llama.cpp/blob/master/LICENSE)) | **No**: `ai_chat.cpp` formats with `use_jinja = false` and keeps its own message list; no tools | Builds from source with Gradle's CMake | Not published to Maven; `minSdk = 33` (Locus: 26); its Gradle CMake build compiles llama.cpp on every build |
| Third-party bindings on Maven Central: llmedge (`io.github.aatricks:llmedge`), Llamatik (`com.llamatik:library`), ffmpegkit-maintained llama-android (wraps b9878), kotlinllamacpp (alpha) ([llmedge](https://awesome.ecosyste.ms/projects/github.com%2Faatricks%2Fllmedge), [Llamatik](https://dev.to/ferranpons/how-to-run-llms-offline-on-android-using-kotlin-407g), [llama-android wiki](https://github.com/ffmpegkit-maintained/llama-android/wiki), [kotlinllamacpp](https://github.com/ljcamargo/kotlinllamacpp)) | Various | Only llmedge documents tool calling | Prebuilt AARs: F-Droid would have to rebuild each from its own sources and pins | Each pins its own, older llama.cpp. Upstream changes chat templates and parsers almost daily (the tag here is `b12066`; llama-android wraps `b9878`) |
| **Build llama.cpp ourselves, with a small JNI bridge** | MIT | **Yes**, via `common/chat.h`: Jinja templates from the GGUF, lazy GBNF grammar per tool call, PEG parser | Rebuilt from source like proot | Chosen |

A git submodule was rejected because the build script can't verify a submodule's commit the way a
pinned tarball hash or a srclib commit check can. Upstream's own Android guide
([docs/android.md](https://github.com/ggml-org/llama.cpp/blob/b12066/docs/android.md)) also builds
with the NDK through CMake (`GGML_NATIVE=OFF`, `GGML_LLAMAFILE=OFF`, `LLAMA_OPENSSL=OFF`).

### Build

`tools/build-llama.sh` takes the source from one of two places:

- **The release tarball**, checked against a pinned SHA-256 (`f7ee7615…`). Releases are tagged
  several times a day ([releases](https://github.com/ggml-org/llama.cpp/releases)).
- **An F-Droid srclib checkout** (`LLAMA_SRC`). The script checks the checkout is at the pinned commit.

It then configures `core/ondevice/src/main/cpp/CMakeLists.txt` for each ABI (arm64-v8a, x86_64)
with NDK r29 (`29.0.14206865`, the NDK proot and the terminal use):

- **`BUILD_SHARED_LIBS=ON` with `GGML_BACKEND_DL=ON`**: ggml's CPU backend is built as variants that
  load at run time.
- **`GGML_CPU_ALL_VARIANTS=ON`**: for Android arm64, ggml builds `armv8.0`, `armv8.2` (+dotprod,
  ±fp16), `armv8.6` (+i8mm), `armv9.0` (+SVE2) and `armv9.2` (+SME) variants
  (`ggml/src/CMakeLists.txt`). `ggml_backend_load_all_from_path(nativeLibraryDir)` loads the best
  one the CPU supports, so a 2018 Cortex-A53 phone and a 2026 phone with SME2 both get their fastest
  kernels. x86_64 (emulators, Chromebooks) gets one SSE4.2 build instead: its 14
  variants would add 20 MB for devices that barely run Locus. The APK already extracts native libraries (`useLegacyPackaging = true`, for proot), which
  this needs.
- **`common` linked statically into `liblocusllama.so` with `--gc-sections`**: unused parts (argument
  parsing, downloads, the HTTP client) are dropped, which saved 3.6 MB on arm64. `ANDROID_STL=c++_shared`,
  because the libraries pass C++ exceptions and objects between them; the NDK's `libc++_shared.so`
  ships with them.
- **`GGML_OPENMP=OFF`**: ggml's own thread pool is used. **`GGML_CPU_KLEIDIAI=OFF`**: see below.
- **16 KB pages**: `-z max-page-size=16384`. The script checks every `LOAD` segment.

| Library (arm64-v8a, stripped) | Bytes |
|---|---|
| `liblocusllama.so` (bridge + llama.cpp `common`) | 2,258,736 |
| `libllama.so` | 3,911,200 |
| `libggml-base.so`, `libggml.so` | 829,720 |
| `libggml-cpu-android_*.so` (7 variants) | 6,267,360 |
| `libc++_shared.so` (from the NDK) | 1,373,744 |
| **Total** | **≈14.6 MB** uncompressed |

APK impact is in [APK size](#apk-size) below.

### arm64 CPU features and GPU offload

- **dotprod / i8mm / SVE / SME**: covered by the CPU variants above. `llama_print_system_info()` is
  logged at load (`LocusOnDevice`). It reports, for example, `DOTPROD = 1 | MATMUL_INT8 = 1 | SME = 1`.
- **KleidiAI** (Arm's micro-kernels, [build.md § Arm KleidiAI](https://github.com/ggml-org/llama.cpp/blob/b12066/docs/build.md#arm-kleidiai)):
  ggml's CMake downloads the KleidiAI tarball at configure time (`FetchContent`, checked only by MD5),
  and F-Droid's build is offline after the srclib fetch. Left for a follow-up: KleidiAI would become
  a second srclib, and its gain would be measured on the phone first.
- **OpenCL on Adreno**: llama.cpp's OpenCL backend is written for Adreno, but it is verified only on
  Adreno 750/810/830/840. It says "A6x GPUs in phones are likely not supported due to the outdated
  driver and compiler" ([OPENCL.md](https://github.com/ggml-org/llama.cpp/blob/b12066/docs/backend/OPENCL.md)).
  It needs the device's `libOpenCL.so`, and it is tuned for Q4_0 and Q8_0. **Vulkan**: upstream's
  Android docs don't recommend it. Each GPU backend adds a library, a fallback path and a driver
  matrix nobody on the team can test, so neither is in v1. LiteRT-LM remains the GPU path for its
  own models.

### APK size

One `assembleDebug` arm64-v8a APK built from this branch, compared with `main`:
see the PR description for the measured numbers. Native libraries are stored compressed, because
`useLegacyPackaging = true` extracts them at install. So the download grows by the compressed size,
and the installed size by the uncompressed ≈14.6 MB. The x86_64 libraries total ≈9.9 MB.

### Reproducible and from source on F-Droid

These are the same measures as for `libtermux.so` and proot (docs/FDROID.md, "What wasn't
reproducible"):

- The source is copied without `.git`, so the tarball and the srclib build the same tree.
- llama.cpp's build info is fixed from the pinned tag and commit, not read from git. The compiler and
  host strings are left empty: they differ between macOS and Linux.
- `-ffile-prefix-map` maps the checkout and the NDK paths. The script fails if a library still
  contains the build path.
- `-Wl,--build-id=none`, and `llvm-strip` and `llvm-objcopy --remove-section .comment`. The script
  fails on a build ID. The one exception is the NDK's own prebuilt `libc++_shared.so`, which is the
  same file on every host.
- The **Native libs** workflow has a `llama` job. It deletes the committed libraries, runs the script
  on `ubuntu-latest` and fails if anything differs. On 2026-11-03 the 18 files built on macOS
  matched its Linux rebuild byte for byte, once one leak was fixed: ggml asks git for "the" commit
  from its source folder, and git found Locus's own repository there, so the script now sets
  `GIT_CEILING_DIRECTORIES`. Should a rebuild ever differ, commit the job's `llama-jniLibs` artifact.

The F-Droid recipe gets a `llama-cpp` srclib at the pinned commit. `rm` deletes
`core/ondevice/src/main/jniLibs/*/lib*.so`, and `build` also runs
`LLAMA_SRC=$$llama-cpp$$ ../tools/build-llama.sh`. `sudo` installs `cmake` and `ninja-build`.
See docs/FDROID.md.

## Tool calling

### How llama.cpp does it

`common_chat_templates_init(model)` reads `tokenizer.chat_template` from the GGUF and parses it with
llama.cpp's own Jinja engine. For each request, `common_chat_session` (`common/chat.h`) does four
things, the same steps llama-server takes for `/v1/chat/completions`:

1. It renders the OpenAI-style messages and tools through the template. `common_chat_msgs_parse_oaicompat`
   and `common_chat_tools_parse_oaicompat` read them, so Locus sends plain OpenAI JSON (`GgufWire`).
2. It picks a format. The formats are a dedicated handler for known families (Gemma 4, MiniMax) or a
   PEG parser derived by rendering the template with and without a tool call (`peg-native`).
3. It builds a **lazy GBNF grammar** with trigger words. Sampling is free until the model starts a
   tool call (`<tool_call>`, `[TOOL_CALLS]`, …). From then on, the call is constrained to the tool's
   JSON schema (`json-schema-to-grammar`), so arguments are valid JSON for that schema.
4. It parses the output, incrementally (`feed`) and at the end (`finish`), into content, reasoning
   and tool calls.

The bridge streams the parsed content and reasoning as they grow. Each tool call's name is announced
once the call is known. Special tokens are rendered only when the parser needs them (the preserved
tokens), as llama-server does.

### Which models get tools

At import, `GgufTemplate.supportsTools` checks whether the template's Jinja code reads a tools
variable. Text that only mentions the word "tools" doesn't count.

Across the 75 templates in llama.cpp's `models/templates` at the pinned tag, it says:

- **Tools** for Qwen 2.5 and 3, Llama 3.1, 3.2 and 3.3, Mistral Nemo and Small, Gemma 4, LFM2.5,
  Granite, Hermes, gpt-oss, GLM, Kimi, DeepSeek V3.2 and V4, and others.
- **Chat only** for Gemma 2, Phi-3.5, SmolLM2 and the DeepSeek R1 distills, whose templates have no
  tools.

`GgufTemplateTest` runs on a sample of them. After the first load, llama.cpp's own detection
(`common_chat_templates_get_caps`, `supports_tool_calls`) can downgrade a model to chat only
(`GgufModels.confirmTools`).

A chat-only model:

- **Model list**: the on-device provider's model list (`ModelDirectory`) reports `tools = false` for
  it, so the per-chat model sheet shows "Can't use here: no tool calling".
- **Sending**: the send warning offers a model that can use tools, or **Send anyway**. Sent anyway,
  no tools are declared (`GgufWire.request(withTools = false)`) and the model answers in plain text.
- **Fallbacks and helpers**: fallbacks already skip on-device providers. Sub-agents skip models known
  to lack tools (`servesHelper`).
- **Settings**: it shows a "Chat only" pill and an explanation.

### Verified on a computer

The bridge was built for macOS and run through a JNI harness with the same request JSON the app
sends, on two models:

- **Qwen2.5-0.5B-Instruct Q4_K_M** (Apache-2.0):
  - Asked for the weather with a `weather` tool, it returned `weather({"city": "Paris"})` with no
    content, format `peg-native`.
  - Given the tool result, the next step reused 187 of 217 prompt tokens from the KV cache and
    answered "The current weather in Paris is 18°C with light rain."
  - A tool result containing `<|im_end|>` was treated as text.
  - Cancel from another thread stopped decoding after 64 tokens.
  - A prompt larger than the context failed with the `context` kind.
- **SmolLM2-135M-Instruct Q8_0** (Apache-2.0): llama.cpp reported `supports_tool_calls: false`,
  matching the import-time check.
- **A non-GGUF file**: llama.cpp reported "invalid magic characters", which Locus classes as a
  damaged file.

## Prompt caching

The request is a pure function of the transcript. The system prompt is frozen per chat, the tool
list is fixed per chat, and history is append-only (`GgufWireTest`). So step N+1's rendered prompt
starts with step N's prompt plus its reply.

The bridge keeps the tokens in the KV cache (`Session::cached`). It removes only what follows the
longest common prefix (`llama_memory_seq_rm`), so a step prefills only the new tool result or
message. Cache reuse is reported as `cacheRead` in the turn's usage.

Two cases fall back to a full prefill:

- **Sliding-window models** (Gemma, some Mistral): a prefix older than the window is checked, as in
  llama-server.
- **A cancelled prefill.**

## Memory and thermal limits

### Memory

**At import** (`GgufMemory`):

- The weights are the file, memory-mapped (`LLAMA_LOAD_MODE_MMAP`): file-backed pages Android can
  reclaim and re-read.
- The KV cache is `layers × context × kv_heads × (key_length + value_length) × 2` bytes (F16).
- Compute buffers and the runtime are counted as 300 MB plus 5% of the weights.
- The context is the largest of 16K, 8K and 4K that the model was trained for and that keeps the
  total within 65% of the phone's memory. On-device agent chats use a compact system prompt at 16K
  (`PromptBudget`).

**Before loading**:

- A model whose estimate exceeds the phone's total memory is refused with that sentence ("Too large"
  in Settings): loading it would only get Locus killed.
- Above 65% of memory, or when Android reports low memory, Settings warns and the load goes ahead.
- llama.cpp failing to allocate the KV cache or compute buffers maps to a "not enough memory" error.

**When Android kills Locus mid-load** (the low-memory killer can't be caught): a marker file written
around `nativeLoad` survives. On the next start the model shows "Ran out of memory", with what to do
about it.

### Lifecycle and heat

GGUF models use the same lifecycle as LiteRT-LM (docs/design/battery-thermal.md), because
`OnDeviceEngines` holds either kind, one at a time:

- **Loading**: a model loads on its first message. Loading the other kind unloads it.
- **Idle unload**: `IdleUnloader` unloads after 10 minutes, 2 minutes when hot or in battery saver,
  and 20 seconds when memory is short.
- **Light mode**: 2 threads and at most 512 output tokens. Threads are set per reply
  (`llama_set_n_threads`), so no reload is needed.
- **Critical heat**: llama.cpp's abort flag stops decoding. The reply keeps its text and ends with
  `HeatStop.note`, and any tool call cut off this way isn't run.
- **Stop**: the same flag stops decoding. It is checked between tokens and inside `llama_decode`
  through the abort callback, so a long prefill stops too.

The default thread count is half the cores, between 2 and 4. llama.cpp runs at the pace of its
slowest thread, so on big.LITTLE phones the fast cores are the useful ones.

## States

| State | Where it shows |
|---|---|
| Reading the header, copying (with progress and Cancel) | On-device models screen, above the lists |
| Not a GGUF, truncated, unknown version, split into parts, vision projector, unsupported or non-chat architecture, not enough storage | Import banner, with "Pick another file". Nothing is kept |
| Short of memory / too large | Pill and banner in the model's sheet. Too large is refused at load |
| Loading, loaded (CPU, load time, memory, last reply's tok/s), unload | Model sheet and row, from `EngineStatus` |
| Out of memory during load (allocation failure, or Locus killed) | Error in chat, "Ran out of memory" pill and banner |
| Damaged file found at load, unsupported architecture, template failure | Error in chat (`GgufErrors`) |
| Chat only | Pill, sheet text, model picker ("Can't use here: no tool calling"), send warning |
| Context full | `CONTEXT_OVERFLOW`, so the agent compacts and retries |
| Deleted while a chat uses it | "This imported model was deleted. Pick another model for this chat." |
| Search: searching, no results, offline, too many requests (429), needs login (gated) | The search screen and a repo's detail, with Try again (and, for gated repos, what to do instead) |
| Checking a file (header), refused (split, projector, not GGUF, private address, http), too large, short on memory, unverified source | The confirm sheet before a download |
| Waiting for Wi-Fi, downloading (speed, time left), paused, checking the SHA-256, failed with Resume or Retry, installed with Use | The download's row and notification |

## Getting models

### Screens

Settings → Models has one **On-device models** row ("2 installed · 3.1 GB"); the chat's model sheet
has **Get an on-device model…**. Both open the on-device screens, which are app routes of their own
(`OnDeviceModelsRoute`, `OnDeviceSearchRoute`, `OnDeviceModelRoute`), code in
`feature/settings/…/ondevice/`:

- **On-device models**: a one-line explainer with **Learn more**; **Downloads** pinned at the top
  while any run, with pause, resume and cancel; **Installed**, LiteRT and GGUF models in one list
  with an engine badge, state, size and Tools or Chat only; **Discover**, the LiteRT and GGUF
  catalogues in one list with filter chips (Fits this phone, on by default; Tools; Under 1 GB;
  LiteRT; llama.cpp). With nothing installed, the top three picks that fit come first. The top
  bar has **Search** and **Add** (from a URL, with validation, or a file).
- **Search Hugging Face**: a search field, sort chips and results; a repo opens its detail.
- **Model detail**: name, publisher, licence with a link, parameters, context, tools, and a fit
  verdict for this phone; the files as a selectable list with size and fit, the recommended one
  (Q4_K_M when it fits) pre-selected; Download, then Use in a new chat, Set as default, Unload and
  Delete once installed. A gated repo says why and what to do instead.

Rows are read by TalkBack as one item ("Qwen3.5 2B, 1.3 GB, fits this phone, supports tools,
Get"), download progress is announced every 10 %, and chips and buttons wrap rather than clip at
large font sizes. Sources were read on 2026-11-03.

### The Hugging Face API

Locus calls four public endpoints on `huggingface.co`, without a token
([Hub API](https://huggingface.co/docs/hub/api), its [OpenAPI description](https://huggingface.co/spaces/huggingface/openapi),
and `huggingface_hub`'s [`HfApi` reference](https://huggingface.co/docs/huggingface_hub/package_reference/hf_api),
checked against live answers recorded in `core/ondevice/src/test/resources/hf/`):

| Call | Used for |
|---|---|
| `GET /api/models?search=…&filter=gguf&sort=downloads\|trendingScore&direction=-1&limit=30&expand[]=downloads&expand[]=likes&expand[]=gated&expand[]=cardData&expand[]=lastModified` | Search. `filter=gguf` keeps repos with GGUF files; `cardData.license` is the licence |
| `GET /api/models/{repo}?expand[]=cardData&expand[]=gated&expand[]=sha` | A repo's licence, whether it's gated (`false`, `"auto"` or `"manual"`), and the commit `main` is at |
| `GET /api/models/{repo}/revision/{rev}` | The commit a branch, tag or `refs/pr/N` is at (`sha`) |
| `GET /api/models/{repo}/tree/{commit}?recursive=true` | Every file with `size`; Git LFS files also have `lfs.oid`, the **SHA-256** of the content, and `lfs.size` |

Files download from `https://huggingface.co/{repo}/resolve/{commit}/{path}`, which answers `302` to
a signed CDN URL (`us.aws.cdn.hf.co`, Xet storage) that honours `Range` with `206` and a
`Content-Range`. A gated repo's files answer `401` with `X-Error-Code: GatedRepo`
([gated models](https://huggingface.co/docs/hub/models-gated)). Too many requests answer `429` with
`RateLimit: "api";r=0;t=<seconds>` (`api` bucket: 500 requests per 5 minutes for anonymous callers
at the time of writing; [rate limits](https://huggingface.co/docs/hub/rate-limits)), which Locus shows
as "Try again in …".

### How each source is verified

| Source | Pinned to | Checked against | Label |
|---|---|---|---|
| Recommended | The commit, file, size and SHA-256 in `catalog.json` | That SHA-256 | Verified |
| Search | The commit `main` is at when the repo is opened | `lfs.oid` from the tree at that commit | Verified |
| URL on huggingface.co (`…/resolve/…` or `…/blob/…`, also `hf.co`) | The link's revision, resolved to a commit when checked | `lfs.oid` at that commit | Verified |
| Any other https URL | The URL | Nothing published: its SHA-256 is computed and shown | **Unverified source**, with a confirm step |

Before any download, Locus reads the file's header (its first few megabytes) from the pinned URL and
runs the import's checks: not a GGUF, unsupported or non-chat architecture, a split part or a vision
projector are refused before the download starts. The header also gives what the confirm step shows:
tool calling, parameters, quantisation and the memory estimate for this phone (`GgufMemory`), with
the same "too large" refusal and "short on memory" warning as an imported model. After the download
the SHA-256 is computed and compared (a mismatch deletes the file), the header is checked again
(`GgufModels.adopt`), and the file moves into `files/gguf/` with one rename. The model's sheet shows
where it came from and its SHA-256.

Search lists a repo's `.gguf` files with size and quantisation. Split parts (`-00001-of-00003`) are
listed but can't be picked: the import refuses them, and loading splits would need
`llama_model_load_from_splits` and every part downloaded, which is left for later. `mmproj` files are
labelled "Vision projector", `mtp-…` files "Draft head" and importance matrices "Not a model".

### Downloads

`GgufDownloads` runs each download as a WorkManager job in the foreground, with the LiteRT-LM
downloads' "Model downloads" notification channel, so it carries on in the background. It:

- checks free storage before starting and while writing (200 MB is left free);
- shows progress, speed and time left in Settings and the notification;
- pauses (the job is cancelled, the `.part` file kept), resumes with `Range: bytes=N-` (a `200` starts
  over; a `206` at another offset is never spliced in), and cancels (the `.part` file is deleted);
- waits for an unmetered network unless Settings → Battery & heat allows mobile data, with "Use
  mobile data" on a waiting download, as for LiteRT-LM;
- writes `files/gguf-downloads/<id>.part` opened with `O_NOFOLLOW`, and a new one with `O_CREAT|O_EXCL`
  (`NOFOLLOW_LINKS`, `CREATE_NEW`), so nothing is written through a link at that name;
- survives Locus being stopped: unfinished downloads are kept in `downloads.json` and show as paused,
  or carry on if WorkManager still holds their job.
- always ends in a state the row shows: every failure, Errors included (a class that failed to
  initialise), becomes "Download failed" with the reason (paths and URLs scrubbed) and Retry or
  Remove, and a job that ends while the row still says it's working (watched through WorkManager)
  does too. Nothing waits on a spinner forever;
- never keeps the same file twice: a file already installed (same SHA-256) is reported as "Already
  on this phone", a second download of a file already downloading carries that one on, and
  `GgufModels.adopt` builds the model record before it moves the file, so a failure can't leave an
  unrecorded `.gguf` behind. At start, such a file left by an earlier build is recorded (a pending
  verified download of it vouches for it) and a byte-for-byte `-2` copy is deleted.

No Room schema changed: downloads and the new model fields (`source`, `sha256`, `verified`) are in the
GGUF JSON index files.

### Network rules

Every request goes through `PublicModelNetwork` (core/data), which is fetch_url's network with no
allowed local hosts: the app's OkHttp client with `FetchDns` (each DNS answer is checked at connect
time, so a name that rebinds to `192.168.x.x`, `10.x`, `127.0.0.1`, link-local or `fc00::/7` fails),
no proxy, and redirects followed by `ModelHttp` one hop at a time. Each hop must be https, carry no
user name or password, and pass `FetchGuard.refusal`. So a pasted URL, or a redirect from one, can't
reach the phone's LAN, a router, or a server running in Locus's Linux. Plain `http://` is refused, and
so is a redirect from https to http. Requests carry only `User-Agent: Locus (on-device model download)`
and `Range`: no token, cookie or anything about the user, and nothing reaches the model.

**No Hugging Face token in v1.** Gated repos (Meta's and Google's own uploads, for example) are
labelled "Needs login" in search and refused with that reason. Ungated re-uploads exist for most of
them. A token would mean a new secret in the vault, a sign-in screen and a rule that it's only ever
sent to huggingface.co (not to the CDN it redirects to); left for a follow-up.

### The recommended list

`core/ondevice/src/main/resources/dev/locus/core/ondevice/gguf/catalog.json`, written and checked by
`tools/gguf-catalog.py` (`--update` re-pins every entry to its repo's current `main`; without it, it
verifies and exits 1 on any difference). For each entry it reads the size and SHA-256 from the tree,
refuses gated repos, and reads the file's GGUF header with a Range request for the architecture,
trained context, KV cache size and whether the template declares tools (the same rule as
`GgufTemplate.supportsTools`). `GgufCatalogTest` checks every entry has a commit, size and SHA-256,
runs on the pinned llama.cpp, is a single-file model and calls tools.

The criteria: small instruct models from their makers or the main GGUF publishers (unsloth,
ggml-org), ungated, Q4_K_M (llama.cpp's usual size and quality balance), whose template declares
tools, from 0.5 to 2.7 GB so there's one for a 4 GB phone and one for a 12 GB one.

| Entry | Repo (publisher) | File | Size | Licence | Why |
|---|---|---|---|---|---|
| Qwen3.5 0.8B | unsloth/Qwen3.5-0.8B-GGUF | Q4_K_M | 0.53 GB | Apache-2.0 | Smallest with tool calling; any phone |
| LFM2.5 1.2B Instruct | LiquidAI/LFM2.5-1.2B-Instruct-GGUF (maker) | Q4_K_M | 0.73 GB | LFM Open License 1.0 (free below $10M yearly revenue; not OSI) | Built for phones: fast prefill, tools |
| Qwen3.5 2B | unsloth/Qwen3.5-2B-GGUF | Q4_K_M | 1.28 GB | Apache-2.0 | The default pick: tools, long context |
| SmolLM3 3B | ggml-org/SmolLM3-3B-GGUF (llama.cpp's org) | Q4_K_M | 1.92 GB | Apache-2.0 | Fully open data and recipe; tools |
| Llama 3.2 3B Instruct | unsloth/Llama-3.2-3B-Instruct-GGUF | Q4_K_M | 2.02 GB | Llama 3.2 Community License | Meta's repo is gated; this copy isn't |
| Granite 4.0 Micro | ibm-granite/granite-4.0-micro-GGUF (maker) | Q4_K_M | 2.10 GB | Apache-2.0 | Tuned for tool calling and instructions |
| Qwen3.5 4B | unsloth/Qwen3.5-4B-GGUF | Q4_K_M | 2.74 GB | Apache-2.0 | Strongest that fits an 8 GB phone |

Considered and left out:

- **Phi-4-mini-instruct** (MIT): the GGUF's template takes tools only from a `tools` field on the
  system message (`message['tools']`), never from the `tools` variable llama.cpp renders with, so
  Locus marks it chat only.
- **Gemma 3 1B/4B**: its template has no tools (chat only). **Gemma 4 E2B/E4B** GGUFs exist
  (ggml-org, Apache-2.0), but Locus already offers Gemma 4 through LiteRT-LM, which can use the GPU.
- **SmolLM2**: chat only (no tools in its template). **Qwen2.5** and **Qwen3**: superseded by Qwen3.5
  at the same sizes.
- **Meta's and Google's own repos**: gated.
- **LFM3 1.7B**: published the day this was written; its revision was still moving.

## Not done

- GPU offload (OpenCL on Adreno, Vulkan), KleidiAI.
- Images (`mtmd` and an mmproj file).
- Thinking: `enable_thinking` is off. Models that always think still stream their reasoning.
- A benchmark for GGUF models (the LiteRT-LM benchmark is unchanged).
- A Hugging Face token for gated repos ([Getting models](#getting-models)).
- Split models (`-00001-of-0000N`): refused, as for imports.
- A chat started on a 16K model and switched to a GGUF model with a smaller context keeps the system
  prompt it was created with. Compaction handles the overflow.
