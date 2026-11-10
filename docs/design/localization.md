# Localisation

Status: shipped in main 2026-11-10. Every module's own UI text is in resources and translated into es, pt (Brazilian) and zh-Hans; `MissingTranslation` and `ExtraTranslation` are lint errors. Not yet tried on the phone or by native speakers. What is still English is listed under [Still English](#still-english). Code: `AppLocale` and `UiText` (core/designsystem), `LanguageSettings.kt` (feature/settings), `HardcodedTextGuardTest` (core/designsystem), `tools/i18n_check.py`, `androidResources` in `app/build.gradle.kts`, `lint.xml`.

Locus ships in English, Spanish, Portuguese (Brazil) and Simplified Chinese. The user picks a language for Locus alone in Settings → Language, or in App info → Language on Android 13 and later.

## Languages and folders

| Language | Tag | Folder |
|---|---|---|
| English (default) | `en-US` | `values` |
| Spanish | `es` | `values-es` |
| Portuguese (Brazil) | `pt` | `values-pt` |
| Simplified Chinese | `zh-Hans` | `values-b+zh+Hans` |

- `values-pt` holds Brazilian Portuguese. Most Portuguese speakers are Brazilian, and a phone set to `pt-PT` falls back to `values-pt` too, so one folder serves both. If European Portuguese ever gets its own text, it goes in `values-pt-rPT`, and `values-pt` stays Brazilian.
- `values-b+zh+Hans` is the BCP 47 form. It matches a phone set to Chinese (China), Singapore or any other Simplified Chinese locale, because Android works out the script from the region. Traditional Chinese (`zh-TW`, `zh-HK`) isn't offered and gets English.
- `app/src/main/res/resources.properties` says `values` is `en-US`. AGP builds the LocaleConfig Android 13+ shows in App info from the folders (`generateLocaleConfig = true`), and `localeFilters` keeps it, and the APK, to these four. Libraries ship dozens of languages, and Android would otherwise offer a language whose screens are English.
- Debug builds turn on the pseudo-locales (`isPseudoLocalesEnabled`): English (XA) stretches and accents every resource string, and Arabic (XB) mirrors the layout. Text that stays plain English in en-XA is still hardcoded.

## The per-app language

Android's own per-app language, through `AppLocale` (core/designsystem), which calls `LocaleManager.applicationLocales` ([official guide](https://developer.android.com/guide/topics/resources/app-languages)). It needs Android 13 (API 33):

- Android stores the choice, keeps it in sync with App info → Language, backs it up, and recreates the activities in it. Compose recomposes and `stringResource` resolves in the new language.
- Below API 33 Locus follows the phone's language and Settings doesn't show the Language row. The guide's only way to choose a language there is AppCompat (`AppCompatDelegate` with an `AppCompatActivity` and an AppCompat theme); Locus stays a Compose `ComponentActivity` app with its platform theme, so it doesn't use it.
- With `generateLocaleConfig`, Android 13+ also lists Locus under Settings → System → Languages → App languages, picker or not.

Changing the language recreates the open screens. Settings → Language lists "Same as phone" (an empty locale list) and the four languages, each named in its own language and never translated, so someone who picked the wrong one can find theirs.

## Strings

- **One `strings.xml` per module**, in that module's `src/main/res/values`, beside the code that shows it. A key is `<module>_<screen>_<element>`: `settings_hub_language_title`, `chat_composer_send`. Text two or more modules share is `common_*` in core/designsystem (`common_cancel`, `common_retry`).
- **Composables** read `stringResource(R.string.…)` and `pluralStringResource(R.plurals.…, count, count)`.
- **Text made outside a composable** (a view model's message, a use case's error) is a `UiText`: `UiText.Res(R.string.backup_saved, size)`, `UiText.Plural(R.plurals.tasks_scheduled, n)`, or `UiText.Plain(serverMessage)` for text that isn't ours. The screen calls `asString()`; a `UiText` argument resolves first. A `UiText.Plural` with no arguments passes its count.
- **Plurals** have every CLDR category the language uses: `one` and `other` in English; `one`, `many` and `other` in Spanish and Portuguese (`many` is for large round numbers such as 1 000 000); `other` only in Chinese. Never build a plural from `if (n == 1)`.
- **Arguments are positional**: `%1$s`, `%2$d`. Translators reorder them; a bare `%s` can't move.
- **Escaping**: `\'` or a string in double quotes for an apostrophe, `\"` for a quote, `\n` for a line break, `%%` for a percent sign in a formatted string, `&lt;` and `&amp;` for XML. `…` is one character (`&#8230;` or the character itself), not three dots.
- **`translatable="false"`** for anything that isn't words: the brand (Locus), product and provider names, URLs, commands, file names, model ids, units Locus doesn't translate.
- **Language names** in the picker are written in their own language, not translated.
- **Text that names a Settings page** ("… in Settings → Battery & heat") passes that page to the link or button shown with it: `SettingsLinkIn(text, destination = SettingsDestination.Battery)`, `SettingsActionFor(text, destination = …)`, `hasSettingsAction(text, destination = …)`, `TaskBudgetEditor(…, costNoteDestination = …)`. Without one they find the page by reading the English "Settings → …" in the text; only English messages from `core/agent`, `core/data` and other untranslated sources still rely on that. Translators don't need to keep the English wording, the word "Settings" or the arrow: the link opens the right page whatever the sentence says.

## What stays English

Anything the model reads or that is stored stays English, in every language:

- tool specs, tool results, system prompts and helper prompts: `core/agent`, `core/llm` and `core/model` text;
- logs, diagnostics and crash reports;
- persisted values: enum names, settings keys, file names Locus writes, workspace paths.

The model answers in the user's language when the user writes in it; the scaffolding around it doesn't change. If the prompts followed the app's language, switching it would also change every chat's cached prefix.

## Guardrails

- `HardcodedTextGuardTest` (core/designsystem) counts string literals handed to Compose text APIs (`Text("…")`, `text =`, `title =`, `subtitle =`, `label =`, `summary =`, `contentDescription =`, `placeholder =`, `LocusChip("…")`, `SectionLabel("…")`, `feedback.show("…")`, `showSnackbar("…")`) per module in app, every feature and core/designsystem, ui, browser, markdown and terminal. A module may not go above its line in `core/designsystem/hardcoded-text-baseline.properties`. When it goes down, the test says so; lower the line with `LOCUS_HARDCODED_TEXT_UPDATE=1 ./gradlew :core:designsystem:testDebugUnitTest --tests '*HardcodedTextGuardTest*'`. The heuristic is a line-level pattern: it misses literals in an `if`, and counts a few that are fine. It only has to be stable.
- Lint: `MissingTranslation` and `ExtraTranslation` are errors (`lint.xml`): a new English string needs its es, pt and zh-Hans translations (or `translatable="false"`) in the same PR, and a removed one goes from every language.
- `python3 tools/i18n_check.py` (`--strict` also fails on a module with English strings and no translations) compares every translation with English: keys, placeholders, plural categories, escaping. Run it with every strings change.

## Migration order

1. **Groundwork** (this): the per-app language, the picker, `UiText`, the guard, lint and this note.
2. **English extraction**, one module per PR: the module's literals move to its `strings.xml` with the keys above, and its baseline line goes down. The English text doesn't change, so the module's screenshots render the same.
3. **Translations**, one module per PR: `values-es`, `values-pt` and `values-b+zh+Hans` for that module, reviewed with the checklist below.
4. **Final PR** (2026-11-10): `MissingTranslation` and `ExtraTranslation` became errors and core/terminal got its three strings. The guard's baseline isn't zero: what is left is preview and sample data, animation labels and number formats, plus the text listed under [Still English](#still-english); each later move lowers it.

## Review checklist

- Every key has the same placeholders, by number and type, in every language.
- Every plural has all the categories its language uses (es and pt: `one`, `many`, `other`; zh: `other`).
- Nothing is cut off or squeezed in es and pt, which often run longer than English: render the module's screens in each language, at 200% font too. The language goes in front of the density qualifier: `tools/render-screens.sh` or `-Plocus.screenshots.dir=<dir> -Plocus.screenshots.density=es-xhdpi` (`pt-xhdpi`, `b+zh+Hans-xhdpi`) with `-Proborazzi.test.record=true`. The render guard writes cut-off titles to `build/reports/render-guard/findings.txt`. Screenshots that tap a button by its English label before capturing only render in English.
- Chinese wraps at any character, and buttons and chips with Chinese text keep their line breaks sensible: check the renders.
- `translatable="false"` is on brand names, URLs, commands and ids, and on nothing a person reads as a sentence.

## Text from the engine

The engine's modules make text the screens show: Linux's health checks, setup steps and Android's kill notice (core/linux, core/data), the self-test and Storage (core/data), the start-up notices, and the catalogues behind Settings → Integrations and Library (integrations, MCP and service templates, provider and email presets, connection checks). The model must keep reading exactly the English it always read, so the split is:

- **Text only the user reads moves into resources** of the module that makes it: `linux_*` in core/linux, `data_*` in core/data. Those modules can't use `UiText` (core/designsystem), so they return an `EngineText` (core/linux): the same idea, a resource id and its arguments, resolved when shown. Screens call `asString()` on it, or `toUiText()` for a view model's state (core/ui, and feature/workspace, which doesn't use core/ui). Enums carry a `@StringRes` (`LinuxFix.labelRes`, `SelfTestFix.labelRes`, `StorageArea.titleRes`, `LinuxPackage.noteRes`).
- **Text the model also reads keeps its English** where it is, and gets a display twin: `LinuxStatus.Installing.stepText` beside `step`, `LinuxStatus.Unavailable.reasonText` beside `reason`, `LinuxHealth.exitText` beside `exitDescription`, `AndroidKill.reasonText`/`fixText`/`fixLeadText` beside `REASON`/`FIX`/`FIX_LEAD`, and `LinuxHeatAdvice.messageText` beside `message` (chat still shows the English one).
- **Catalogues keep their English fields** (a template's description is saved into the connector the model reads; a preset's name is saved as the provider's name), and core/ui maps each entry, by id or by its English label, to resources (`CatalogText.kt`, `ui_catalog_*`): `descriptionText`, `authSummaryText`, `labelText`, `noteText` and the like. Product names stay as they are.
- **Stored English notices** ("Locus couldn't unlock your saved keys…") stay stored in English; `StorageNotices.text` shows the known ones in the app's language, and an unknown one (from an older version) as stored.
- **Sizes** (`LinuxHealth.bytesText`) keep their units and write the number the language's way (1,5 KB); `LinuxHealth.bytes` stays the English form for logs and tests.

Tests: core/linux and core/data check which resource and arguments each case gets; `LinuxEngineTextTest` (feature/workspace), `EngineTextTest` (feature/settings), `CatalogTextTest` (core/ui) and core/data's `EngineEnglishTest` render them in English and compare with the English the code wrote before, word for word, and fail when a catalogue entry has no resource.

## Still English

- **Text from the engine not covered above**: exception messages core/data throws for the user (`LibraryRepository`'s "needs an https:// address", `ProviderAccess.missing`, `ServiceAddress` checks, Linux repair failures), which screens show as they are.
- **Catalogue names that are words** stay English because they are saved or match logos: provider preset names ("On this phone (Gemma 4, offline)", "Custom") and MCP template names ("Files", "Knowledge graph").
- **What the chat shows while the agent works has that split**: core keeps writing the English (for the model, exports, logs and Diagnostics) and hands the same text over as data, which the app words from its strings. Tool steps and activity lines: `toolLabelText` / `toolActivityOf` (core/model, `ToolPhrase`, `ToolActivity`), worded by `localizedToolLabel` / `localizedToolActivity` (core/designsystem `ToolLabels.kt`). Provider errors: `LlmException.text` (`LlmErrorText`, core/llm); the run's overflow error, budget notices, progress lines and notices: `ContextOverflowText`, `BudgetReached` (core/agent), `RunNote` and `RunState.*Note` (core/data); receipts, budget figures, `RunFailure.advice` and on-device speeds from their data. All worded in feature/chat `RunTextUi.kt`. Tests check that every English string says exactly the core's English. What stays English there: the provider's own words inside a message, persisted run details (a receipt's stored reason), what the agent or a backup list adds after a message ("Backup models skipped: …"), tool `describe()` text on approval cards (also the audit log's), the notification and widget lines (core/data), Settings → Permissions rule titles, and the voice mode.
- **View-model messages and helpers that tests compare in English** in chat, settings and tasks (notes in each extraction PR). They move with their tests.
- **The voice-mode spoken layer** in chat.

## Native-speaker review

Translated by AI with a second review pass, not yet by native speakers. Worth a native look first:

- The approval buttons Allow, Deny, Always and Never in each language: they must be unambiguous.
- Spanish "Equipos" for SSH machines (it can read as "teams"); maybe "Máquinas" or "Servidores".
- Sentences that insert another string: a policy word into the project editor text, "helper"/"skill" into Library's changed-outside banners, verb phrases into workflow text. Gender and word order may not always agree in es and pt.
- Short chips at 200% font in es and pt ("Predeterminado", "Por revisar").
- Brazilian wording: "Saúde do Linux", "Status do Locus".

## Glossary

One term per concept, reused across modules. New translations follow this table; change a row only together with every string that uses it.

| English | Español | Português | 简体中文 |
|---|---|---|---|
| workspace | espacio de trabajo | espaço de trabalho | 工作区 |
| task (scheduled) | tarea | tarefa | 任务 |
| scheduled task | tarea programada | tarefa agendada | 定时任务 |
| schedule (verb) | programar | agendar | 设置定时 |
| skill | habilidad | habilidade | 技能 |
| memory | memoria | memória | 记忆 |
| connector | conector | conector | 连接器 |
| helper (sub-agent) | ayudante | ajudante | 助手 |
| agent | agente | agente | 智能体 |
| workflow | flujo de trabajo | fluxo de trabalho | 工作流 |
| provider | proveedor | provedor | 提供商 |
| model | modelo | modelo | 模型 |
| chat | chat | chat | 对话 |
| run (noun) / run (verb) | ejecución / ejecutar | execução / executar | 运行 |
| action (a tool call) | acción | ação | 操作 |
| tool | herramienta | ferramenta | 工具 |
| approval / approve | aprobación / aprobar | aprovação / aprovar | 批准 |
| allow | permitir | permitir | 允许 |
| deny | rechazar | negar | 拒绝 |
| reject (a draft) | rechazar | rejeitar | 驳回 |
| dismiss | descartar | dispensar | 忽略 |
| reviewer (safety) | revisor | revisor | 审查器 |
| trash | papelera | lixeira | 回收站 |
| delete | eliminar | excluir | 删除 |
| remove | quitar | remover | 移除 |
| save | guardar | salvar | 保存 |
| settings | ajustes | configurações | 设置 |
| sign in / login (saved) | iniciar sesión / inicio de sesión | entrar / login | 登录 |
| password | contraseña | senha | 密码 |
| key (API key) | clave | chave | 密钥 |
| token | token | token | 令牌 |
| phone | teléfono | telefone | 手机 |
| file / folder | archivo / carpeta | arquivo / pasta | 文件 / 文件夹 |
| tab (browser) | pestaña | aba | 标签页 |
| terminal | terminal | terminal | 终端 |
| package (apk) | paquete | pacote | 软件包 |
| digest | resumen | resumo | 摘要 |
| event (task trigger) | evento | evento | 事件 |
| quiet hours | horas de silencio | horário silencioso | 免打扰时段 |
| reminder | recordatorio | lembrete | 提醒 |
| receipt (of a run) | recibo | recibo | 回执 |
| failed | con error | com falha | 失败 |
| preview | vista previa | prévia | 预览 |
| library | biblioteca | biblioteca | 资料库 |
| insights | estadísticas | estatísticas | 统计 |
| take over / hand back (browser) | tomar control / devolver | assumir / devolver | 接管 / 交还 |

Notes:

- Plurals in Spanish and Portuguese copy `other` into `many` where the noun needs no change ("1 000 000 acciones"); fix any `many` that needs "de" ("1 000 000 de acciones") when a string is next reviewed.
- Stepper chips and other tight spots may use a shorter word than the table (onboarding: "Espacio" / "Espaço" for the Workspace step).
- Phrases where English builds a sentence around another string (see the fragments listed in the translation PR) keep the English word order only where the target language allows it.
