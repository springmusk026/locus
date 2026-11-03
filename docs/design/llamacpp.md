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
- **Import through the system file picker only.** The file is copied into app storage. There is no
  download from URLs in v1.
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
| Reading the header, copying (with progress and Cancel) | Settings → Models → On-device models |
| Not a GGUF, truncated, unknown version, split into parts, vision projector, unsupported or non-chat architecture, not enough storage | Import banner, with "Pick another file". Nothing is kept |
| Short of memory / too large | Pill and banner in the model's sheet. Too large is refused at load |
| Loading, loaded (CPU, load time, memory, last reply's tok/s), unload | Model sheet and row, from `EngineStatus` |
| Out of memory during load (allocation failure, or Locus killed) | Error in chat, "Ran out of memory" pill and banner |
| Damaged file found at load, unsupported architecture, template failure | Error in chat (`GgufErrors`) |
| Chat only | Pill, sheet text, model picker ("Can't use here: no tool calling"), send warning |
| Context full | `CONTEXT_OVERFLOW`, so the agent compacts and retries |
| Deleted while a chat uses it | "This imported model was deleted. Pick another model for this chat." |

## Downloads

There is no URL download in v1. The existing model manager downloads only the two catalogue files,
pinned by URL, size and SHA-256 (`OnDeviceCatalog`). Nothing comparable can vouch for a URL a user
pastes. A Hugging Face search with a quantisation picker is in the roadmap ("llama.cpp via Arm's
Kotlin binding" item, now this one plus search).

## Not done

- GPU offload (OpenCL on Adreno, Vulkan), KleidiAI.
- Images (`mtmd` and an mmproj file).
- Thinking: `enable_thinking` is off. Models that always think still stream their reasoning.
- A benchmark for GGUF models (the LiteRT-LM benchmark is unchanged).
- Hugging Face search and download.
- A chat started on a 16K model and switched to a GGUF model with a smaller context keeps the system
  prompt it was created with. Compaction handles the overflow.
