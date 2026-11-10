# Localisation

Status: groundwork built 2026-11-09. Settings → Language and the per-app language work; the app's text is still English and hardcoded, and moves to resources module by module (see [Migration order](#migration-order)). Not yet tried on the phone. Code: `LanguageSettings.kt` (feature/settings), `UiText` (core/designsystem), `HardcodedTextGuardTest` (core/designsystem), `androidResources` in `app/build.gradle.kts`, `lint.xml`.

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
- Lint: `MissingTranslation` and `ExtraTranslation` are warnings in `lint.xml` while the translations arrive. The last PR makes them errors.

## Migration order

1. **Groundwork** (this): the per-app language, the picker, `UiText`, the guard, lint and this note.
2. **English extraction**, one module per PR: the module's literals move to its `strings.xml` with the keys above, and its baseline line goes down. The English text doesn't change, so the module's screenshots render the same.
3. **Translations**, one module per PR: `values-es`, `values-pt` and `values-b+zh+Hans` for that module, reviewed with the checklist below.
4. **Final PR**: the baseline is zero, `MissingTranslation` and `ExtraTranslation` become errors, and the guard fails on any literal.

## Review checklist

- Every key has the same placeholders, by number and type, in every language.
- Every plural has all the categories its language uses (es and pt: `one`, `many`, `other`; zh: `other`).
- Nothing is cut off or squeezed in es and pt, which often run longer than English: render the module's screens (`recordRoborazziDebug`) in each language, at 200% font too.
- Chinese wraps at any character, and buttons and chips with Chinese text keep their line breaks sensible: check the renders.
- `translatable="false"` is on brand names, URLs, commands and ids, and on nothing a person reads as a sentence.

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
