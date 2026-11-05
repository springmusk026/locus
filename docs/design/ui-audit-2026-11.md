# UI audit, November 2026

Every screen, sheet and dialog in Locus, rendered and checked against the [unslop](https://heydamir.design/unslop) rules (signs, tokens and its checklist; CC BY 4.0, Damir Sokolovsky), the deslopify-ui review checklist, and this repo's design system ([design-system.md](design-system.md)), translated to Compose and our tokens. The on-device model screens and the ModelSheet's on-device rows are left out (#179 owns them).

**How it was made.** The screenshot tests (docs/design/design-system.md, "Review gate") rendered every screen in five variants: 360 and 411dp, light and dark, and 360dp at 200% font. That's 357 screens and 1,784 images. They were rendered at 2x with `tools/render-screens.sh` into `renders/audit/before/<module>/<name>_<variant>.png`, in the session scratchpad. The image paths below are relative to `renders/audit/`, and after-images sit beside them under `after/`. The render guard's automatic findings (text below WCAG AA, raw ids) are folded into the lines below; its false positives are marked.

**Format.** One line per issue: severity, what's wrong and the fix, the source file:line (as of the audit, on `ui-review-gate`), and the image. `[x]` marks a fixed issue, with its PR.

- **S1**: broken, unreadable or misleading. Invisible text, the wrong state shown, content lost.
- **S2**: clearly off the system. Misalignment, truncated meaning, contrast below AA, spacing off the scale, decoration without meaning, wrong copy.
- **S3**: polish.

**Totals at audit time:** 26 S1, 177 S2, 119 S3. Issues in shared components (LocusSheet's header, Banner, SwitchRow, SectionLabel and SectionNote insets, StatusLine colours, LocusCard padding) were found in all three parts, so they appear in each part. Each is fixed once, in `core/designsystem`, and ticked in every part.

## Chat

Scope: every render in `renders/audit/before/feature-chat/`. I looked at 360-dark, 360-light and 360-light-font200 for each screen. Source paths are relative to the repo (worktree agent-ab8b1e4868f63b5df). I skipped the on-device-model heat sheets (`sheet-heat-hold`, `sheet-heat-hold-battery`) because #179 owns them. The LocusSheet header bug noted under Cross-cutting shows up there too.

Render-guard notes:
- **False positives: all voice-* findings.** `#55595A on #000000` in the light variant and `"Microphone on" #A8281F on #C6322A`. `VoiceScreenshots` renders `VoiceScreen` without the screen's `Box(background)`. The PNG is transparent (alpha 0), so translucent fills (the orb, the chip's 14% container) were read as solid on black.
- **Also artefacts:** composer-states, sheet-more and voice-tap/approval findings in dark (`#B4B8B5 on #FFFFFF`, `receipt-march.pdf #000000 on #282A29`). The composer test has no `OnScreen` background, so its surfaces are transparent.
- **Real:** the light-theme Caution and Live status text (4.02 / 3.85 / 4.17:1), the diff +/− colours, the request ids, and the side-question history text (2.36 / 3.04:1). They are folded in below.

### Cross-cutting (fix once, many screens)

#### Shared components
- [x] S2 · SectionLabel hard-codes `padding(start = Spacing.lg)`, and callers' `Modifier.padding(start = 0.dp)` can't undo it. Labels sit 16dp right of the cards they head on Home, All chats, Search, Project and Project editor, and 16dp right of the 24dp sheet edge in sheets (Model, Tools, Budget, What was sent, Project). Give SectionLabel no start inset and let the container own the edge · core/designsystem/src/main/kotlin/dev/locus/core/designsystem/Components.kt:276 · before/feature-chat/home_360-dark.png · fixed in #186 → after/feature-chat/home_360-dark.png
- [x] S2 · (known) SectionNote's start inset (`Spacing.lg`) puts notes at 36-40dp in sheets while cards start at 24dp. Callers then add another `start = Spacing.xs` · core/designsystem/src/main/kotlin/dev/locus/core/designsystem/Components.kt:293 · before/feature-chat/sheet-budget-default_360-dark.png · fixed in #186 → after/feature-chat/sheet-budget-default_360-dark.png
- [x] S1 · The LocusSheet header puts the title (maxLines 2) and the action Button in one Row with no width cap. A long action ("Preview what's copied", "Project settings", "Copy JSON", "Compact now") squeezes the title and subtitle into a column one character wide at 200% font (unreadable), and wraps them to 2-3 lines at 360. Move the action under the title when it doesn't fit, or cap its width · core/designsystem/src/main/kotlin/dev/locus/core/designsystem/Components.kt:551 · before/feature-chat/sheet-remote-folder_360-light-font200.png · fixed in #186 → after/feature-chat/sheet-remote-folder_360-light-font200.png
- [x] S2 · ConfirmSheet's two weighted buttons have no maxLines or centred text. "Switch to claude-opus-5-5" and "Run on this phone" wrap to 2-3 left-aligned lines inside the pill, and at 200% the labels overflow it. Stack the buttons vertically when a label doesn't fit · core/designsystem/src/main/kotlin/dev/locus/core/designsystem/Sheets.kt:86 · before/feature-chat/sheet-send-warning_360-dark.png · fixed in #186 → after/feature-chat/sheet-send-warning_360-dark.png
- [x] S2 · StatusLine draws Caution and Live text in `tone.color()`. On the light background that measures 4.02:1 (caution #B0680A) and 3.85-4.17:1 (live #1E8E50). It affects "Waiting for your approval", "Waiting for your answer", "Paused at the task budget", "Paused: phone is hot", "Paused: battery saver", "Running a command", "Browsing", "Working" and "1 action needs your approval". Use `tone.onContainer()` for text · core/designsystem/src/main/kotlin/dev/locus/core/designsystem/Components.kt:342 · before/feature-chat/chat-approval_360-light.png · fixed in #186 → after/feature-chat/home_360-light.png
- [x] S2 · Diff colours are `added = status.live`, so "+42" and "+60" measure 4.17:1 on white. The +/− gutter marks on the tinted line backgrounds measure 2.97:1 (+) and 3.71:1 (−). Use the on-container shades for text on tints · core/designsystem/src/main/kotlin/dev/locus/core/designsystem/Diff.kt:38 · before/feature-chat/sheet-diff_360-light.png · fixed in #186 → after/feature-chat/sheet-diff_360-light.png
- [x] S2 · KeyValueRow `dense` gives the label a fixed weight with maxLines 1 and then wraps it, which breaks mid-word: "output_config.eff/ort", "max_tok/ens", "Respons/e id", and "Outco…", "Chec…", "Appro…" at 200%. Let the label take its natural width, or stack label above value at large font · core/designsystem/src/main/kotlin/dev/locus/core/designsystem/Controls.kt:152 · before/feature-chat/sheet-what-was-sent_360-light-font200.png · fixed in #192 → after-open/feature-chat/sheet-what-was-sent_360-light-font200.png
- [x] S3 · RunDots shows red dots for earlier failed runs right next to "passed on run 3 of 3", with no legend. It reads as "still failing" at a glance. Fade the earlier runs, or show only the last run's tone · core/designsystem/src/main/kotlin/dev/locus/core/designsystem/ChatParts.kt:135 · before/feature-chat/chat-finished_360-dark.png · fixed in #192 → after-open/feature-chat/chat-finished_360-dark.png

#### Screenshot harness (fix the tests so the renders can be trusted)
- [x] S2 · Every SettingsLink renders faded and disabled ("Settings → Permissions", "Settings → Agent", "Diagnostics"), and the no-key error shows no "Open Models" action. The tests don't wrap content in `ProvideSettingsLinks`, so `enabled = links != null` is false. The faded links also look like a real contrast bug to a reviewer. Provide links in `ScreenshotTest.snap` · core/designsystem/src/main/kotlin/dev/locus/core/designsystem/Links.kt:43 · before/feature-chat/sheet-budget-default_360-light.png · fixed in #186 → P → after/feature-chat/sheet-budget-default_360-light.png
- [x] S3 · `VoiceScreenshots` renders `VoiceScreen` with no background, so the PNGs are transparent and every voice contrast finding is a false positive. Wrap the content in `OnScreen` · feature/chat/src/test/kotlin/dev/locus/feature/chat/voice/VoiceScreenshots.kt:26 · before/feature-chat/voice-listening_360-light.png · fixed in #185 → after/feature-chat/voice-listening_360-dark.png
- [x] S3 · The composer-states test also has no background, so the dark variant shows a white-looking composer and the guard flags 2.01:1 and 1.29:1. Wrap it in `OnScreen` · feature/chat/src/test/kotlin/dev/locus/feature/chat/SheetScreenshots.kt:236 · before/feature-chat/composer-states_360-dark.png · fixed in #188 → after/feature-chat/composer-states_360-dark.png

#### Chat top bar and composer (every chat screen)
- [x] S1 · At 200% the chat title shrinks to "…" or "Fix check…" because the budget chip, ContextMeter and share button keep their full width. The title is lost. Drop the budget chip and context % into the overflow or a second line at large font · feature/chat/src/main/kotlin/dev/locus/feature/chat/ChatScreen.kt:689 · before/feature-chat/chat-running_360-light-font200.png · fixed in #188 → after/feature-chat/chat-running_360-light-font200.png
- [x] S2 · While a run is going, the composer's model pill is hard-clipped to "claude-opus-5-" at 360 and "claud" at 200%, with no ellipsis. The Aside and stop buttons take the row. Hide the label behind the icon while running, or ellipsize correctly · feature/chat/src/main/kotlin/dev/locus/feature/chat/LocusComposer.kt:601 · before/feature-chat/chat-running_360-dark.png · fixed in #188 → after/feature-chat/chat-running_360-dark.png
- [x] S3 · The per-reply meta ("9k in · 118 out", "5m 12s · cost unknown · 52.3k in · 640 out · 84%") is mono and wraps under the action icons at 200%. "84%" has no label (it is the cache share). Write "84% cached" and let the meta take its own line · feature/chat/src/main/kotlin/dev/locus/feature/chat/ChatScreen.kt:1672 · before/feature-chat/chat-finished_360-light-font200.png · fixed in #188 → after/feature-chat/chat-finished_360-light-font200.png
- [x] S3 · Thought rows and the Insights launcher tile use a sparkle icon (decorative AI tell). Use a neutral glyph that means "reasoning" or "chart" · feature/chat/src/main/kotlin/dev/locus/feature/chat/ChatControlsUi.kt:356 · before/feature-chat/chat-running_360-dark.png · fixed in #188 → after/feature-chat/chat-running_360-dark.png

### Home and conversations

#### home
- [x] S2 · At 200% the five launcher tiles read "Li…", "Ta…", "Fil…", "Ac…", "In…": every label is lost. Wrap to two rows or drop to icon plus a shorter label at large font · feature/chat/src/main/kotlin/dev/locus/feature/chat/ConversationsScreen.kt:528 · before/feature-chat/home_360-light-font200.png · fixed in #188 → after/feature-chat/home_360-light-font200.png
- [x] S2 · The composer floats over the end of the list: the "Yesterday · OpenRouter" row shows half-hidden under it. List bottom padding is `padding + Spacing.lg` and doesn't include the ask bar's height · feature/chat/src/main/kotlin/dev/locus/feature/chat/ConversationsScreen.kt:357 · before/feature-chat/home_360-dark.png · fixed in #192 → after-open/feature-chat/home_360-dark.png
- [x] S2 · The project chips row is cut mid-chip ("Portfolio s", and "+ New" is hidden at 200%), with no fade or scroll hint · feature/chat/src/main/kotlin/dev/locus/feature/chat/ConversationsScreen.kt:820 · before/feature-chat/home_360-light-font200.png · fixed in #188 → after/feature-chat/home_360-light-font200.png
- [x] S2 · At 200% the featured card and top-bar status lose meaning: "Waiting for your …" and "1 action needs yo…". Allow 2 lines · feature/chat/src/main/kotlin/dev/locus/feature/chat/ConversationsScreen.kt:363 · before/feature-chat/home_360-light-font200.png · fixed in #188 → after/feature-chat/home_360-light-font200.png
- [x] S2 · Caution and Live status text fails AA in light: "Waiting for your approval" 4.01-4.35:1, "Working" 4.17:1, "1 action needs your approval" 4.02:1 (see StatusLine) · core/designsystem/src/main/kotlin/dev/locus/core/designsystem/Components.kt:342 · before/feature-chat/home_360-light.png · fixed in #186 → after/feature-chat/home_360-light.png
- [x] S3 · The featured approval card has a warm radial glow in dark, which is decoration. The amber status line already carries the state · feature/chat/src/main/kotlin/dev/locus/feature/chat/ConversationsScreen.kt:363 · before/feature-chat/home_360-dark.png · fixed in #188 → after/feature-chat/home_360-dark.png

#### home-all-chats
- [x] S3 · The chat count "1 chat" is set in mono (mono is for ids and paths). Use SupportingText in the supporting role · feature/chat/src/main/kotlin/dev/locus/feature/chat/ConversationsScreen.kt:669 · before/feature-chat/home-all-chats_360-dark.png · fixed in #188 → after/feature-chat/home-all-chats_360-dark.png
- [x] S3 · At 200% the search placeholder is clipped without ellipsis ("Search titles and"). Shorten it to "Search chats" · feature/chat/src/main/kotlin/dev/locus/feature/chat/ConversationsScreen.kt:678 · before/feature-chat/home-all-chats_360-light-font200.png · fixed in #188 → after/feature-chat/home-all-chats_360-light-font200.png

#### home-loading
- [x] S1 · Loading is an empty Box. It shows a blank screen with no composer and no LoadingState, so it reads as broken. Use LoadingState or a skeleton of the list · feature/chat/src/main/kotlin/dev/locus/feature/chat/ConversationsScreen.kt:263 · before/feature-chat/home-loading_360-dark.png · fixed in #188 → after/feature-chat/home-loading_360-dark.png

#### home-no-chats
- [x] S2 · The first-run note is a bare SupportingText with an extra `start = Spacing.xs` inset, so it starts 4dp off the card edge. It is not an EmptyState and has no action. Align it to the card column or use EmptyState · feature/chat/src/main/kotlin/dev/locus/feature/chat/ConversationsScreen.kt:394 · before/feature-chat/home-no-chats_360-dark.png · fixed in #188 → after/feature-chat/home-no-chats_360-dark.png
- [x] S3 · The Projects row subtitle "Chats with their own folder, instr…" is cut at 360. Shorten it to "Chats with their own folder and memory" · feature/chat/src/main/kotlin/dev/locus/feature/chat/ConversationsScreen.kt:812 · before/feature-chat/home-no-chats_360-light.png · fixed in #188 → after/feature-chat/home-no-chats_360-light.png

#### home-no-provider
- [x] S3 · The empty state's hero tile is a sparkle with no meaning. Use a key or plug icon for "connect a model" · feature/chat/src/main/kotlin/dev/locus/feature/chat/ConversationsScreen.kt:267 · before/feature-chat/home-no-provider_360-dark.png · fixed in #188 → after/feature-chat/home-no-provider_360-dark.png

#### home-quiet
- [x] S2 · The "All chats" row is cut in half by the floating composer, with a further row peeking out below. The list's bottom padding doesn't clear the ask bar (same cause as home) · feature/chat/src/main/kotlin/dev/locus/feature/chat/ConversationsScreen.kt:357 · before/feature-chat/home-quiet_360-dark.png · fixed in #192 → after-open/feature-chat/home-quiet_360-dark.png

#### home-search
- [x] S2 · Result meta is truncated where it matters: "Yesterday · On another …" (which branch?) and snippets cut at "opti…". At 200% a snippet keeps one word. Give the meta its own line, or shorten it to "Other branch" · feature/chat/src/main/kotlin/dev/locus/feature/chat/ConversationsScreen.kt:607 · before/feature-chat/home-search_360-dark.png · fixed in #188 → after/feature-chat/home-search_360-dark.png

#### home-search-no-matches
- [x] S3 · The "no matches" message is a SectionNote with an extra inset (34dp vs 16dp cards) and offers no action. Clearing the project filter or the query would help. Use EmptyState with "Clear search" · feature/chat/src/main/kotlin/dev/locus/feature/chat/ConversationsScreen.kt:693 · before/feature-chat/home-search-no-matches_360-dark.png · fixed in #188 → after/feature-chat/home-search-no-matches_360-dark.png

#### home-search-pending
- [x] S1 · While a search is running the screen shows "0 chats" and an empty list, with no spinner. It reads as "no results". Show InlineSpinner or "Searching…" while `searchPending`, and hide the count · feature/chat/src/main/kotlin/dev/locus/feature/chat/ConversationsScreen.kt:669 · before/feature-chat/home-search-pending_360-dark.png · fixed in #188 → after/feature-chat/home-search-pending_360-dark.png

#### home-search-titles-only
- No issues found beyond the SectionLabel inset above.

### Projects

#### projects
- [x] S3 · Row supporting text truncates the last-used time ("3 da…", "· …"). Drop the folder path, which repeats the project name, and keep chats · time · feature/chat/src/main/kotlin/dev/locus/feature/chat/ProjectsScreen.kt:106 · before/feature-chat/projects_360-dark.png · fixed in #188 → after/feature-chat/projects_360-dark.png

#### projects-empty
- [x] S3 · The top-bar "+" duplicates the EmptyState's "New project" button. Hide the "+" while the list is empty · feature/chat/src/main/kotlin/dev/locus/feature/chat/ProjectsScreen.kt:93 · before/feature-chat/projects-empty_360-dark.png · fixed in #188 → after/feature-chat/projects-empty_360-dark.png

#### project
- [x] S3 · The folder path appears twice: the mono subtitle in the top bar and the "Workspace folder" row. Drop the subtitle · feature/chat/src/main/kotlin/dev/locus/feature/chat/ProjectsScreen.kt:131 · before/feature-chat/project_360-dark.png · fixed in #188 → after/feature-chat/project_360-dark.png
- [x] S3 · The Model row uses a sparkle IconTile (decoration). The Memory subtitle "This project's memory, then your gen…" is cut at 360. Shorten it to "Project, then general" · feature/chat/src/main/kotlin/dev/locus/feature/chat/ProjectsScreen.kt:163 · before/feature-chat/project_360-light.png · fixed in #188 → after/feature-chat/project_360-light.png

#### project-no-chats
- [x] S3 · The empty chat list is only a SectionLabel ("No chats yet") with nothing under it. Add a one-line note, or point to "New chat in this project" · feature/chat/src/main/kotlin/dev/locus/feature/chat/ProjectsScreen.kt:181 · before/feature-chat/project-no-chats_360-dark.png · fixed in #188 → after/feature-chat/project-no-chats_360-dark.png

#### project-not-found
- [x] S2 · The edit pencil still shows in the top bar for a project that doesn't exist. The error has no action. Hide Edit and add "Back to projects" · feature/chat/src/main/kotlin/dev/locus/feature/chat/ProjectsScreen.kt:132 · before/feature-chat/project-not-found_360-dark.png · fixed in #188 → after/feature-chat/project-not-found_360-dark.png

#### project-editor-edit
- [x] S2 · Permission group rows list raw tool ids as body text ("code_outline, copy_path, delete_pa…", "git_commit, git_inspect, linux_servi…"). Show a count and plain words ("12 tools · 2 ask") · feature/chat/src/main/kotlin/dev/locus/feature/chat/ProjectEditorScreen.kt:176 · before/feature-chat/project-editor-edit_360-dark.png · fixed in #188 → after/feature-chat/project-editor-edit_360-dark.png
- [x] S2 · Fields are Material OutlinedTextFields (Name, Instructions) instead of TextArea in a LocusCard, and the notes under them sit at 28-36dp while the Permissions note sits at 16dp. Use TextArea and one note inset · feature/chat/src/main/kotlin/dev/locus/feature/chat/ProjectEditorScreen.kt:109 · before/feature-chat/project-editor-edit_360-light.png · fixed in #188 → after/feature-chat/project-editor-edit_360-light.png
- [x] S3 · At 200% the segmented control reads "Project and …". Model rows are cut at 360 ("Used by new chats in t…"). The phrase "so their cached prompt stays valid" is jargon and appears in three notes. Say "existing chats keep their settings" once · feature/chat/src/main/kotlin/dev/locus/feature/chat/ProjectEditorScreen.kt:114 · before/feature-chat/project-editor-edit_360-light-font200.png · fixed in #188 → after/feature-chat/project-editor-edit_360-light-font200.png

#### project-editor-new
- [x] S3 · Save is enabled while Name and Workspace folder are empty (`enabled = !state.loading`). Disable it until both are filled · feature/chat/src/main/kotlin/dev/locus/feature/chat/ProjectEditorScreen.kt:75 · before/feature-chat/project-editor-new_360-dark.png · fixed: Save is off until the project has a name, with a hint under Name in #192 → after-open/feature-chat/project-editor-new_360-dark.png

#### project-editor-new-named
- No issues found beyond project-editor-new.

### Chat transcript

#### chat-empty
- [x] S3 · The share button shows (faded) on a chat with no messages. Hide it until there is something to share · feature/chat/src/main/kotlin/dev/locus/feature/chat/ChatScreen.kt:698 · before/feature-chat/chat-empty_360-dark.png · fixed in #188 → after/feature-chat/chat-empty_360-dark.png

#### chat-conversation
- [x] S3 · Follow-up suggestion chips run off the right edge with no fade or scroll cue ("Ho" at 200%) · feature/chat/src/main/kotlin/dev/locus/feature/chat/ChatScreen.kt:746 · before/feature-chat/chat-conversation_360-light-font200.png · fixed in #188 → after/feature-chat/chat-conversation_360-light-font200.png

#### chat-running
- [x] S2 · "Running a command" in Live text measures 3.85:1 in light (StatusLine) · core/designsystem/src/main/kotlin/dev/locus/core/designsystem/Components.kt:342 · before/feature-chat/chat-running_360-light.png · fixed in #186 → after/feature-chat/chat-running_360-light.png
- [x] S3 · At 200% the ChecklistStrip shows "2 of 4 done · Fixin…" and the current step's name is lost. Allow 2 lines · feature/chat/src/main/kotlin/dev/locus/feature/chat/ChatControlsUi.kt:293 · before/feature-chat/chat-running_360-light-font200.png · fixed in #188 → after/feature-chat/chat-running_360-light-font200.png

#### chat-running-streaming
- [x] S2 · Failing test names are ellipsized to one line ("retries a declined c…"), which hides the one fact the user needs. Let them wrap to 2 lines · feature/chat/src/main/kotlin/dev/locus/feature/chat/VerificationCard.kt:136 · before/feature-chat/chat-running-streaming_360-dark.png · fixed in #188 → after/feature-chat/chat-running-streaming_360-dark.png
- [x] S3 · The "Output" TextButton is indented about 8dp right of the detail column because of its own content padding. Align its text to the column · feature/chat/src/main/kotlin/dev/locus/feature/chat/VerificationCard.kt:141 · before/feature-chat/chat-running-streaming_360-dark.png · fixed in #188 → after/feature-chat/chat-running-streaming_360-dark.png

#### chat-finished
- [x] S1 · The changes card title is "Changed 4…" at 360 and only "…" at 200%. The file count and title are lost to +/− and "Undo all". Put the counts on a second line · feature/chat/src/main/kotlin/dev/locus/feature/chat/ChangesCard.kt:138 · before/feature-chat/chat-finished_360-light-font200.png · fixed in #188 → after/feature-chat/chat-finished_360-light-font200.png
- [x] S2 · "+60" measures 4.17:1 on white (diff colours, above) · core/designsystem/src/main/kotlin/dev/locus/core/designsystem/Diff.kt:38 · before/feature-chat/chat-finished_360-light.png · fixed in #186 → after/feature-chat/chat-finished_360-light.png

#### chat-cards
- [x] S2 · The receipt line shows a green check and "Done" while the same line says "checks failed". The signals are mixed. Use Caution tone and icon when any check failed · feature/chat/src/main/kotlin/dev/locus/feature/chat/ReceiptCard.kt:137 · before/feature-chat/chat-cards_360-dark.png · fixed in #188 → after/feature-chat/chat-cards_360-dark.png
- [x] S3 · The whole receipt sentence is in mono. Keep mono for the figures only · feature/chat/src/main/kotlin/dev/locus/feature/chat/ReceiptCard.kt:137 · before/feature-chat/chat-cards_360-dark.png · fixed in #188 → after/feature-chat/chat-cards_360-dark.png
- [x] S2 · "Paused: battery saver" measures 4.02:1 in light (PausedChip → StatusLine) · feature/chat/src/main/kotlin/dev/locus/feature/chat/HeatUi.kt:84 · before/feature-chat/chat-cards_360-light.png · fixed in #188 → after/feature-chat/chat-cards_360-light.png

#### chat-long
- No issues found beyond the cross-cutting title and meta items.

#### chat-plan
- [x] S2 · At 200% "Approve and run" breaks mid-word ("Appr/ove/and/run") in a narrow circular button, because three buttons share one row. Stack the buttons at large font · feature/chat/src/main/kotlin/dev/locus/feature/chat/ChatScreen.kt:1443 · before/feature-chat/chat-plan_360-light-font200.png · fixed in #188 → after/feature-chat/chat-plan_360-light-font200.png
- [x] S3 · The plan card carries a glow wash. Keep glow for the single card a screen is about, or drop it · feature/chat/src/main/kotlin/dev/locus/feature/chat/ChatScreen.kt:1443 · before/feature-chat/chat-plan_360-dark.png · fixed in #188 → after/feature-chat/chat-plan_360-dark.png

#### chat-approval
- [x] S2 · At 200% "Allow" breaks to "All/ow" in a circle and "Don't ask …" loses its scope. Stack Details/Deny/Allow and let the scope wrap · feature/chat/src/main/kotlin/dev/locus/feature/chat/ChatScreen.kt:1476 · before/feature-chat/chat-approval_360-light-font200.png · fixed in #188 → after/feature-chat/chat-approval_360-light-font200.png
- [x] S3 · The approval card has a warm radial glow in dark (`glow = caution`), which is decoration. The "Waiting for your approval" line above it repeats the card and leaves about 40dp of empty space. Drop the glow, and drop the status line while the card is visible · feature/chat/src/main/kotlin/dev/locus/feature/chat/ChatScreen.kt:1451 · before/feature-chat/chat-approval_360-dark.png · fixed in #188 → after/feature-chat/chat-approval_360-dark.png

#### chat-approval-reviewer
- [x] S2 · The reviewer's reason text is Caution colour on the card: 4.35:1 (and 4.21:1 at 200%), and it is cut at 2 lines. Use `onCautionContainer` and let it wrap · feature/chat/src/main/kotlin/dev/locus/feature/chat/ChatScreen.kt:1461 · before/feature-chat/chat-approval-reviewer_360-light.png · fixed in #188 → after/feature-chat/chat-approval-reviewer_360-light.png
- [x] S2 · At 200% the approval title and URL are truncated and the bottom of the card is clipped by the dock · feature/chat/src/main/kotlin/dev/locus/feature/chat/ChatScreen.kt:1443 · before/feature-chat/chat-approval-reviewer_360-light-font200.png · fixed: the dock gets 60% of the height while a card waits and scrolls Allow/Deny into view; no tile at 200% in #192 → after-open/feature-chat/chat-approval-reviewer_360-light-font200.png

#### chat-question
- [x] S2 · "Waiting for your answer" measures 4.02:1 in light (StatusLine) · core/designsystem/src/main/kotlin/dev/locus/core/designsystem/Components.kt:342 · before/feature-chat/chat-question_360-light.png · fixed in #186 → after/feature-chat/chat-question_360-light.png

#### chat-question-multi
- [x] S2 · At 200% the card's Send button is cut off by the dock: the transcript's bottom padding doesn't clear the expanded dock · feature/chat/src/main/kotlin/dev/locus/feature/chat/QuestionCard.kt:91 · before/feature-chat/chat-question-multi_360-light-font200.png · fixed: same dock change, Send scrolls into view in #192 → after-open/feature-chat/chat-question-multi_360-light-font200.png

#### chat-queued-aside
- [x] S1 · "Cancel" wraps to "Cance/l" at 360 in the queued-message row. Give the button its natural width (no weight squeeze) · feature/chat/src/main/kotlin/dev/locus/feature/chat/SideQuestionUi.kt:105 · before/feature-chat/chat-queued-aside_360-dark.png · fixed in #188 → after/feature-chat/chat-queued-aside_360-dark.png
- [x] S3 · In the side question card the body starts at the card edge while the title is indented after its icon. Align the body to the title column · feature/chat/src/main/kotlin/dev/locus/feature/chat/SideQuestionUi.kt:105 · before/feature-chat/chat-queued-aside_360-dark.png · fixed in #188 → after/feature-chat/chat-queued-aside_360-dark.png

#### chat-interrupted-queued
- [x] S3 · A lone ↻ icon sits on the left of the interrupted row with no label. Add "Resume" text, or drop the icon · feature/chat/src/main/kotlin/dev/locus/feature/chat/ChatScreen.kt:724 · before/feature-chat/chat-interrupted-queued_360-dark.png · fixed in #188 → after/feature-chat/chat-interrupted-queued_360-dark.png

#### chat-hot-paused
- [x] S2 · At 200% the "Not now" and "Stop services" buttons collide and clip. The banner title is repeated in its body. Stack the actions with `actionsBelow` · feature/chat/src/main/kotlin/dev/locus/feature/chat/HeatUi.kt:82 · before/feature-chat/chat-hot-paused_360-light-font200.png · fixed in #188 → after/feature-chat/chat-hot-paused_360-light-font200.png

#### chat-budget-pause
- [x] S2 · "Paused at the task budget" appears twice (status line and banner). The Blocked strip truncates both the command and the reason at 360. Keep one, and let the reason wrap · feature/chat/src/main/kotlin/dev/locus/feature/chat/ChatScreen.kt:1424 · before/feature-chat/chat-budget-pause_360-dark.png · fixed in #188 → after/feature-chat/chat-budget-pause_360-dark.png

#### chat-browser-handoff
- [x] S2 · "Browsing" measures 3.85:1 in light (StatusLine Live) · core/designsystem/src/main/kotlin/dev/locus/core/designsystem/Components.kt:342 · before/feature-chat/chat-browser-handoff_360-light.png · fixed in #186 → after/feature-chat/chat-browser-handoff_360-light.png

#### chat-error-chatgpt
- [x] S2 · The error body shows a raw request id: "…gave no reason (request id req_8f2c41d0e9)". Keep the id in Failure details only, and say what to do ("Try again, or switch model") · feature/chat/src/main/kotlin/dev/locus/feature/chat/ChatScreen.kt:1536 · before/feature-chat/chat-error-chatgpt_360-dark.png · fixed in #188 → after/feature-chat/chat-error-chatgpt_360-dark.png
- [x] S3 · The dismiss ✕ sits alone on its own row under the actions. Put it in the banner header · feature/chat/src/main/kotlin/dev/locus/feature/chat/ChatScreen.kt:1547 · before/feature-chat/chat-error-chatgpt_360-dark.png · fixed: Banner(onDismiss) puts the close button in the header in #192 → after-open/feature-chat/chat-error-chatgpt_360-dark.png

#### chat-error-no-key
- [x] S3 · "Settings → Models" shows as plain text with only "Try again". In production `SettingsActionFor` adds "Open Models", but the render can't show it (harness: no ProvideSettingsLinks) · feature/chat/src/main/kotlin/dev/locus/feature/chat/ChatScreen.kt:1536 · before/feature-chat/chat-error-no-key_360-dark.png · fixed in #188 → Models" shows as plain text with only "Try again → after/feature-chat/chat-error-no-key_360-dark.png

#### chat-above-composer
- [x] S2 · Earlier side-question text in the dock is 2.36:1 in light and 3.04:1 in dark, too faint to read. Use SupportingText (onSurfaceVariant) · feature/chat/src/main/kotlin/dev/locus/feature/chat/SideQuestionUi.kt:105 · before/feature-chat/chat-above-composer_360-light.png · fixed in #188 → after/feature-chat/chat-above-composer_360-light.png
- [x] S2 · "Runs at the next step" is Caution text on the caution container: 3.39:1. Use `onCautionContainer` · feature/chat/src/main/kotlin/dev/locus/feature/chat/ReviewerBlocks.kt:61 · before/feature-chat/chat-above-composer_360-light.png · fixed in #188 → after/feature-chat/chat-above-composer_360-light.png
- [x] S1 · At 200% the second Blocked strip collapses to "Bloc ke…" and its content is lost. The answer chips sit one per row with large gaps · core/designsystem/src/main/kotlin/dev/locus/core/designsystem/ChatParts.kt:154 · before/feature-chat/chat-above-composer_360-light-font200.png · fixed in #188 → after/feature-chat/chat-above-composer_360-light-font200.png

#### composer-states
- [x] S3 · The dark variant is unusable as a reference: transparent background (harness, above). In light, the attachment chip name is fine, and the queued text "Also update the README" follows the composer. No production issue confirmed · feature/chat/src/test/kotlin/dev/locus/feature/chat/SheetScreenshots.kt:236 · before/feature-chat/composer-states_360-dark.png · fixed in #188 → after/feature-chat/composer-states_360-dark.png

#### check-rows
- [x] S2 · At 200% "Exit status not reliable: the command pipes or…" is cut, which hides the reason. Allow wrapping (`maxLines = 2` is too tight at large font) · feature/chat/src/main/kotlin/dev/locus/feature/chat/VerificationCard.kt:129 · before/feature-chat/check-rows_360-light-font200.png · fixed in #188 → after/feature-chat/check-rows_360-light-font200.png
- [x] S3 · The "Output" TextButton text is indented about 10dp right of the detail column · feature/chat/src/main/kotlin/dev/locus/feature/chat/VerificationCard.kt:141 · before/feature-chat/check-rows_360-dark.png · fixed in #188 → after/feature-chat/check-rows_360-dark.png

#### helper-rows
- [x] S2 · A partial helper shows a "Partial result" chip and also "timed out, returned what it had" in Caution text (4.35:1 on white). That says the same thing twice and fails AA. Keep the chip, and put the meta in SupportingText · feature/chat/src/main/kotlin/dev/locus/feature/chat/SubagentRow.kt:133 · before/feature-chat/helper-rows_360-light.png · fixed in #188 → after/feature-chat/helper-rows_360-light.png
- [x] S2 · At 200% helper titles truncate to "Resear…" and "Compa…", so you can't tell the helpers apart · feature/chat/src/main/kotlin/dev/locus/feature/chat/SubagentRow.kt:89 · before/feature-chat/helper-rows_360-light-font200.png · fixed in #188 → after/feature-chat/helper-rows_360-light-font200.png

#### receipt-lines
- [x] S3 · The receipt is one mono sentence. At 200% it truncates to "1 a…" (approvals lost). Use the supporting role, mono only for figures, and allow 3 lines · feature/chat/src/main/kotlin/dev/locus/feature/chat/ReceiptCard.kt:137 · before/feature-chat/receipt-lines_360-light-font200.png · fixed in #188 → after/feature-chat/receipt-lines_360-light-font200.png

#### sent-file-cards
- [x] S3 · At 200% "Save to device" wraps under the other actions. Shorten it to "Save" · feature/chat/src/main/kotlin/dev/locus/feature/chat/SentFileCard.kt:66 · before/feature-chat/sent-file-cards_360-light-font200.png · fixed in #188 → after/feature-chat/sent-file-cards_360-light-font200.png

### Changes card and its sheets

#### changes-card
- [x] S1 · The title is "Changed 3 …" at 360 (the file count is cut) and disappears at 200%. Rows read "6 minut…". Put the counts on a second line and let the time wrap · feature/chat/src/main/kotlin/dev/locus/feature/chat/ChangesCard.kt:138 · before/feature-chat/changes-card_360-dark.png · fixed in #188 → after/feature-chat/changes-card_360-dark.png
- [x] S2 · "+42", "+37", "+3" and "+2" measure 4.17:1 on white (diff colours) · feature/chat/src/main/kotlin/dev/locus/feature/chat/ChangesCard.kt:229 · before/feature-chat/changes-card_360-light.png · fixed in #188 → after/feature-chat/changes-card_360-light.png

#### changes-card-all-undone
- [x] S3 · Undone file names are 3.32:1. This is deliberate (disabled) and probably acceptable, but strike-through or an "Undone" label would carry the state without relying on low contrast · feature/chat/src/main/kotlin/dev/locus/feature/chat/ChangesCard.kt:226 · before/feature-chat/changes-card-all-undone_360-light.png · fixed in #188 → after/feature-chat/changes-card-all-undone_360-light.png

#### changes-card-many
- [x] S2 · The many-files variant shows only the header with a flat bottom edge, and the "README.md" row is greyed (3.32:1). It looks clipped rather than collapsed. Give the collapsed card a "+N more files" row · feature/chat/src/main/kotlin/dev/locus/feature/chat/ChangesCard.kt:112 · before/feature-chat/changes-card-many_360-dark.png · fixed in #188 → after/feature-chat/changes-card-many_360-dark.png

#### sheet-diff
- [x] S2 · The edit history lists raw tool ids ("edit_file", "write_file") as row titles. Say "Edited" or "Wrote" · feature/chat/src/main/kotlin/dev/locus/feature/chat/ChangesCard.kt:260 · before/feature-chat/sheet-diff_360-dark.png · fixed in #188 → after/feature-chat/sheet-diff_360-dark.png
- [x] S2 · The +/− gutter marks are 2.97:1 and 3.71:1 on their line tints (diff colours) · core/designsystem/src/main/kotlin/dev/locus/core/designsystem/Diff.kt:38 · before/feature-chat/sheet-diff_360-light.png · fixed in #186 → after/feature-chat/sheet-diff_360-light.png
- [x] S3 · Wrapped diff lines are not hanging-indented, so a continuation looks like a new line. Indent wraps past the gutter · feature/chat/src/main/kotlin/dev/locus/feature/chat/ChangesCard.kt:260 · before/feature-chat/sheet-diff_360-dark.png · fixed in #192 → after-open/feature-chat/sheet-diff_360-dark.png

#### sheet-diff-binary
- No issues found.

#### sheet-diff-changed-since
- [x] S3 · The "Changed again since" row ignores the time column the other rows use. Align it · feature/chat/src/main/kotlin/dev/locus/feature/chat/ChangesCard.kt:260 · before/feature-chat/sheet-diff-changed-since_360-dark.png · fixed in #188 → after/feature-chat/sheet-diff-changed-since_360-dark.png

#### sheet-diff-edit-open
- No issues found beyond the diff colours (no font200 render exists for this one).

#### sheet-diff-too-large
- [x] S2 · At 200% the sheet title breaks mid-word (LocusSheet header squeezed by the action) · core/designsystem/src/main/kotlin/dev/locus/core/designsystem/Components.kt:557 · before/feature-chat/sheet-diff-too-large_360-light-font200.png · fixed in #186 → after/feature-chat/sheet-diff-too-large_360-light-font200.png

#### sheet-diff-undone
- No issues found beyond the diff colours.

#### sheet-undo-all
- [x] S3 · At 200% the title loses the file count ("Undo changes to …") · feature/chat/src/main/kotlin/dev/locus/feature/chat/ChangesCard.kt:373 · before/feature-chat/sheet-undo-all_360-light-font200.png · fixed in #188 → after/feature-chat/sheet-undo-all_360-light-font200.png

#### sheet-undo-blocked
- [x] S2 · The sheet says why undo is blocked but offers no next step (open the file, or undo the others). Add the action · feature/chat/src/main/kotlin/dev/locus/feature/chat/ChangesCard.kt:373 · before/feature-chat/sheet-undo-blocked_360-dark.png · fixed: the reason, then what still works, and "View the diff" in #192 → after-open/feature-chat/sheet-undo-blocked_360-dark.png

#### sheet-undo-changed-since
- [x] S3 · The title uses the full path, and at 200% the file name is the part that gets cut. Title with the file name and put the path in the subtitle · feature/chat/src/main/kotlin/dev/locus/feature/chat/ChangesCard.kt:373 · before/feature-chat/sheet-undo-changed-since_360-light-font200.png · fixed in #188 → after/feature-chat/sheet-undo-changed-since_360-light-font200.png

#### sheet-check-runs
- [x] S3 · "Show output" is indented off the row text, and the dividers start at about 35dp while the text starts at about 21dp. Use one inset · feature/chat/src/main/kotlin/dev/locus/feature/chat/VerificationCard.kt:186 · before/feature-chat/sheet-check-runs_360-dark.png · fixed in #188 → after/feature-chat/sheet-check-runs_360-dark.png

#### sheet-check-runs-build
- [x] S3 · The title "Build failed" is repeated as the first row. "Hide output" is indented like above · feature/chat/src/main/kotlin/dev/locus/feature/chat/VerificationCard.kt:150 · before/feature-chat/sheet-check-runs-build_360-dark.png · fixed in #188 → after/feature-chat/sheet-check-runs-build_360-dark.png

### Failure details sheet

#### sheet-failure-details
- [x] S2 · The body repeats the raw request id ("…request id req_8f2c41d0e9") that the table already shows. Drop it from the prose and say what to try · feature/chat/src/main/kotlin/dev/locus/feature/chat/FailureDetailsSheet.kt:43 · before/feature-chat/sheet-failure-details_360-dark.png · fixed in #188 → after/feature-chat/sheet-failure-details_360-dark.png
- [x] S3 · Key/value values sit higher than their labels (mono value on a smaller line height). At 200% "Respons/e id" breaks (KeyValueRow) · core/designsystem/src/main/kotlin/dev/locus/core/designsystem/Controls.kt:152 · before/feature-chat/sheet-failure-details_360-light-font200.png · fixed: label and value share a baseline; long words go over the value in #192 → after-open/feature-chat/sheet-failure-details_360-light-font200.png

#### sheet-failure-details-429
- [x] S2 · The summary is the provider's raw text, with no wait time or next step ("Rate limited: try again in N s, or switch model"). It also shows the raw id "req_011CU7xR2" · feature/chat/src/main/kotlin/dev/locus/feature/chat/FailureDetailsSheet.kt:43 · before/feature-chat/sheet-failure-details-429_360-dark.png · fixed in #188 → after/feature-chat/sheet-failure-details-429_360-dark.png

#### sheet-failure-details-chatgpt-limit
- No issues found beyond the shared KeyValueRow and SettingsLink items.

#### sheet-failure-details-gateway-html
- [x] S3 · The raw Cloudflare ray id "8f2a1c9e4b7d03a1-FRA" appears in the body text. Keep it only in the table, labelled "Gateway id" · feature/chat/src/main/kotlin/dev/locus/feature/chat/FailureDetailsSheet.kt:43 · before/feature-chat/sheet-failure-details-gateway-html_360-dark.png · fixed: the body never had it after #188; the table now labels a Cloudflare ray "Gateway id" in #192 → after-open/feature-chat/sheet-failure-details-gateway-html_360-dark.png

#### sheet-failure-details-network
- [x] S2 · The summary is a raw Java exception ("Software caused connection abort") with no explanation or action. Map it to "The connection dropped. Check the network and try again" · feature/chat/src/main/kotlin/dev/locus/feature/chat/FailureDetailsSheet.kt:43 · before/feature-chat/sheet-failure-details-network_360-dark.png · fixed in #188 → after/feature-chat/sheet-failure-details-network_360-dark.png

#### sheet-failure-details-repeated
- [x] S2 · Same raw request id in the body as sheet-failure-details · feature/chat/src/main/kotlin/dev/locus/feature/chat/FailureDetailsSheet.kt:43 · before/feature-chat/sheet-failure-details-repeated_360-dark.png · fixed in #188 → after/feature-chat/sheet-failure-details-repeated_360-dark.png

### Helper transcript sheets

#### sheet-helper-transcript
- [x] S2 · The sheet body is indented about 12dp more than the title and subtitle, giving two left edges in one sheet. Drop the extra padding inside the list · feature/chat/src/main/kotlin/dev/locus/feature/chat/SubagentRow.kt:227 · before/feature-chat/sheet-helper-transcript_360-dark.png · fixed in #188 → after/feature-chat/sheet-helper-transcript_360-dark.png

#### sheet-helper-transcript-partial
- [x] S2 · The "Partial result" banner text is ellipsized ("its tool results and t…"), so the explanation is cut. Let the banner wrap · feature/chat/src/main/kotlin/dev/locus/feature/chat/SubagentRow.kt:234 · before/feature-chat/sheet-helper-transcript-partial_360-dark.png · fixed in #188 → after/feature-chat/sheet-helper-transcript-partial_360-dark.png

#### sheet-helper-transcript-running
- [x] S3 · At 200% the tool rows truncate to "Fetched hono.d…" · feature/chat/src/main/kotlin/dev/locus/feature/chat/SubagentRow.kt:227 · before/feature-chat/sheet-helper-transcript-running_360-light-font200.png · fixed in #188 → after/feature-chat/sheet-helper-transcript-running_360-light-font200.png

#### sheet-helper-steer
- [x] S3 · At 200% the title "Steer Researcher …" loses "#2". The field is a bare outlined box rather than TextArea in a card · feature/chat/src/main/kotlin/dev/locus/feature/chat/SubagentRow.kt:268 · before/feature-chat/sheet-helper-steer_360-light-font200.png · fixed in #188 → after/feature-chat/sheet-helper-steer_360-light-font200.png

#### sheet-helper-steer-empty
- No issues found beyond sheet-helper-steer.

### Chat sheets

#### sheet-budget-default
- [x] S3 · The note runs "Change the default in" and then a separate faded link line. Long dense paragraph, plus SectionNote at 36dp (cross-cutting) · feature/chat/src/main/kotlin/dev/locus/feature/chat/ChatControlsUi.kt:99 · before/feature-chat/sheet-budget-default_360-dark.png · fixed in #188 → after/feature-chat/sheet-budget-default_360-dark.png

#### sheet-budget-own-running
- [x] S1 · The Tokens and Cost option rows are hard-clipped at the card edge ("1l", "$1"). The last options are hidden, with no scroll cue or wrap. Use a wrapping chip row (FlowRow) · feature/chat/src/main/kotlin/dev/locus/feature/chat/ChatControlsUi.kt:101 · before/feature-chat/sheet-budget-own-running_360-dark.png · fixed in #186 → after/feature-chat/sheet-budget-own-running_360-light.png

#### sheet-effort
- No issues found beyond the SectionNote inset and faded SettingsLink (harness).

#### sheet-mode
- No issues found beyond the SectionNote inset.

#### sheet-more
- [x] S3 · At 200% row subtitles cut at 2 lines. The footnote sits at 36dp. The guard's dark "Message Locus on #FFFFFF" is a false positive (the composer under the scrim) · feature/chat/src/main/kotlin/dev/locus/feature/chat/LocusComposer.kt:603 · before/feature-chat/sheet-more_360-light-font200.png · fixed in #188 → after/feature-chat/sheet-more_360-light-font200.png

#### sheet-tokens
- [x] S2 · The "Compact now" header action squeezes the title to 2 lines at 360. At 200% the title and subtitle break per character ("Cont/ext", "Anthro/pic") (LocusSheet header) · feature/chat/src/main/kotlin/dev/locus/feature/chat/ChatScreen.kt:1637 · before/feature-chat/sheet-tokens_360-light-font200.png · fixed in #188 → after/feature-chat/sheet-tokens_360-light-font200.png
- [x] S3 · Number formats are mixed on one sheet ("212k" next to "9,400" and "41,800"), because the format changes at 100k. Use one format · feature/chat/src/main/kotlin/dev/locus/feature/chat/ChatScreen.kt:1704 · before/feature-chat/sheet-tokens_360-dark.png · fixed in #188 → after/feature-chat/sheet-tokens_360-dark.png

#### sheet-tokens-nearly-full
- No issues found beyond sheet-tokens.

#### sheet-tokens-unknown-window
- No issues found beyond sheet-tokens.

#### sheet-memory-read
- [x] S3 · At 200% note names truncate ("garden app s…", "checkout t…") because the Forget label keeps its full width. The summary row drops "notes" without an ellipsis · feature/chat/src/main/kotlin/dev/locus/feature/chat/MemoryReadUi.kt:101 · before/feature-chat/sheet-memory-read_360-light-font200.png · fixed in #188 → after/feature-chat/sheet-memory-read_360-light-font200.png

#### sheet-memory-forget
- No issues found.

#### sheet-model
- [x] S2 · The search field and section labels (Favourites, Recent, All models) sit at about 40dp while cards and the title sit at 24dp (SectionLabel inset). Provider rows show "3 mod…" cut even at 360 · feature/chat/src/main/kotlin/dev/locus/feature/chat/ModelSheet.kt:149 · before/feature-chat/sheet-model_360-dark.png · fixed in #188 → after/feature-chat/sheet-model_360-dark.png
- [x] S3 · At 200% model names truncate ("Claude Op…", "Google: Ge…") because the check and star keep their width. Allow 2 lines · feature/chat/src/main/kotlin/dev/locus/feature/chat/ModelSheet.kt:148 · before/feature-chat/sheet-model_360-light-font200.png · fixed in #188 → after/feature-chat/sheet-model_360-light-font200.png

#### sheet-model-loading
- No issues found.

#### sheet-tools
- [x] S3 · The "Tools" and "Connectors" labels are 16dp right of the cards (SectionLabel inset) · feature/chat/src/main/kotlin/dev/locus/feature/chat/ChatControlsUi.kt:199 · before/feature-chat/sheet-tools_360-dark.png · fixed in #188 → after/feature-chat/sheet-tools_360-dark.png

#### sheet-project-current
- [x] S2 · Cards sit at about 36dp while the title sits at 24dp. The Memory subtitle "…then y…" is cut at 360. "Take out of the project" has no icon and misaligns with the icon rows · feature/chat/src/main/kotlin/dev/locus/feature/chat/ChatProjectSheet.kt:59 · before/feature-chat/sheet-project-current_360-dark.png · fixed in #188 → after/feature-chat/sheet-project-current_360-dark.png

#### sheet-project-move
- [x] S3 · The "Move to" label sits at 40dp and the cards at 36dp, against a 24dp title · feature/chat/src/main/kotlin/dev/locus/feature/chat/ChatProjectSheet.kt:78 · before/feature-chat/sheet-project-move_360-dark.png · fixed in #188 → after/feature-chat/sheet-project-move_360-dark.png

#### sheet-project-none
- [x] S3 · A paragraph is stuffed into the sheet subtitle and uses jargon ("cached prompt stays valid"). Move it to a SectionNote in plain words · feature/chat/src/main/kotlin/dev/locus/feature/chat/ChatProjectSheet.kt:43 · before/feature-chat/sheet-project-none_360-dark.png · fixed in #188 → after/feature-chat/sheet-project-none_360-dark.png

#### sheet-receipt
- [x] S2 · "Checks: 2 passed · 2 failed" is all red, so the passed count reads as failed. Colour only the failed part, or use Caution · feature/chat/src/main/kotlin/dev/locus/feature/chat/ReceiptCard.kt:168 · before/feature-chat/sheet-receipt_360-dark.png · fixed in #188 → after/feature-chat/sheet-receipt_360-dark.png
- [x] S3 · Values in rows with a chevron (Files, Checks) start left of the others, so the value column is not straight. The Approvals chips sit off the label's baseline. "Outside cont…" is cut at 360 · feature/chat/src/main/kotlin/dev/locus/feature/chat/ReceiptCard.kt:212 · before/feature-chat/sheet-receipt_360-dark.png · fixed: the chevron sits in the value column, chips on the label's baseline, labels wrap whole in #192 → after-open/feature-chat/sheet-receipt_360-dark.png

#### sheet-receipt-failed
- [x] S2 · Outcome shows raw "HTTP 529: Overloaded" with no next step. Map it to "The provider was overloaded. Try again later or switch model" · feature/chat/src/main/kotlin/dev/locus/feature/chat/ReceiptCard.kt:153 · before/feature-chat/sheet-receipt-failed_360-dark.png · fixed in #188 → after/feature-chat/sheet-receipt-failed_360-dark.png

#### sheet-chat-remote
- [x] S2 · The body paragraph is inset about 44dp against a 24dp title. At 200% the "Project settings" action squeezes the title to "Com ma…" and the subtitle breaks per character (LocusSheet header) · feature/chat/src/main/kotlin/dev/locus/feature/chat/RemoteUi.kt:65 · before/feature-chat/sheet-chat-remote_360-light-font200.png · fixed in #188 → after/feature-chat/sheet-chat-remote_360-light-font200.png

#### sheet-remote-choose
- [x] S2 · The subtitle shows the raw tool id "run_command". Say "commands, services and test runs". The card is inset 36dp. At 200% every row is "Run on buil…" · feature/chat/src/main/kotlin/dev/locus/feature/chat/RemoteUi.kt:138 · before/feature-chat/sheet-remote-choose_360-dark.png · fixed in #188 → after/feature-chat/sheet-remote-choose_360-dark.png

#### sheet-remote-choose-empty
- [x] S3 · The "Add an SSH machine" row renders faded and looks disabled although it is the only way forward · feature/chat/src/main/kotlin/dev/locus/feature/chat/RemoteUi.kt:138 · before/feature-chat/sheet-remote-choose-empty_360-dark.png · fixed in #188 → after/feature-chat/sheet-remote-choose-empty_360-dark.png

#### sheet-remote-folder
- [x] S1 · At 200% the title and subtitle collapse into a column one character wide beside "Preview what's copied" and can't be read. At 360 they still wrap to 2-3 lines (LocusSheet header) · feature/chat/src/main/kotlin/dev/locus/feature/chat/RemoteUi.kt:191 · before/feature-chat/sheet-remote-folder_360-light-font200.png · fixed in #188 → after/feature-chat/sheet-remote-folder_360-light-font200.png
- [x] S2 · The warning banner is ellipsized ("credentials a…"), which hides which secrets are affected. The error "~/locus is not writable by basanta." gives no fix (choose another folder, or chmod). Let the banner wrap and add the action · feature/chat/src/main/kotlin/dev/locus/feature/chat/RemoteUi.kt:196 · before/feature-chat/sheet-remote-folder_360-dark.png · fixed in #188 → after/feature-chat/sheet-remote-folder_360-dark.png

#### sheet-remote-linking
- [x] S3 · The Meter is inset 44dp against a 24dp title, and the step has no Cancel · feature/chat/src/main/kotlin/dev/locus/feature/chat/RemoteUi.kt:239 · before/feature-chat/sheet-remote-linking_360-dark.png · fixed: Cancel stops the copy (the link is saved only after it); the meter sits on the sheet edge in #192 → after-open/feature-chat/sheet-remote-linking_360-dark.png

#### sheet-remote-preview
- [x] S1 · At 200% the title and path collapse into a one-character column. At 360 the title takes 3 lines beside the long action (LocusSheet header) · feature/chat/src/main/kotlin/dev/locus/feature/chat/RemoteUi.kt:213 · before/feature-chat/sheet-remote-preview_360-light-font200.png · fixed in #188 → after/feature-chat/sheet-remote-preview_360-light-font200.png

#### sheet-remote-unlink
- [x] S2 · The confirm label "Run on this phone" wraps to 2 left-aligned lines at 360 and overflows the pill at 200% (ConfirmSheet) · core/designsystem/src/main/kotlin/dev/locus/core/designsystem/Sheets.kt:106 · before/feature-chat/sheet-remote-unlink_360-dark.png · fixed in #186 → after/feature-chat/sheet-remote-unlink_360-dark.png

#### sheet-send-warning
- [x] S2 · "Switch to claude-opus-5-5" wraps to 3 left-aligned lines in the pill. A third action, "Send anyway", floats as a TextButton between the note and the buttons. The subtitle repeats "deepseek-v4:" after the title. At 200% the buttons fall off the bottom · feature/chat/src/main/kotlin/dev/locus/feature/chat/SendWarningUi.kt:29 · before/feature-chat/sheet-send-warning_360-dark.png · fixed: full-width buttons, most useful first, Send anyway last in #192 → after-open/feature-chat/sheet-send-warning_360-dark.png

#### sheet-send-warning-no-suggestion
- [x] S2 · The copy says "Pick a model that can … in the model sheet" but offers no button to open it. Add "Choose model" · feature/chat/src/main/kotlin/dev/locus/feature/chat/SendWarningUi.kt:26 · before/feature-chat/sheet-send-warning-no-suggestion_360-dark.png · fixed: "Choose model" opens the model sheet with the message back in the composer in #192 → after-open/feature-chat/sheet-send-warning-no-suggestion_360-dark.png

#### sheet-share-chat
- [x] S3 · The action TextButtons are indented 12-16dp from the text column. Align their labels · feature/chat/src/main/kotlin/dev/locus/feature/chat/ShareChat.kt:82 · before/feature-chat/sheet-share-chat_360-dark.png · fixed in #188 → after/feature-chat/sheet-share-chat_360-dark.png

#### sheet-what-was-sent
- [x] S2 · The params table breaks keys mid-word ("output_config.eff/ort" at 360, "max_tok/ens" at 200%) (KeyValueRow). The "Copy JSON" action squeezes the title to "What was se…" at 200% · core/designsystem/src/main/kotlin/dev/locus/core/designsystem/Controls.kt:152 · before/feature-chat/sheet-what-was-sent_360-dark.png · fixed: the params table uses the dense KeyValueRow; the header action sits under the title since #186 in #192 → after-open/feature-chat/sheet-what-was-sent_360-dark.png

#### sheet-what-was-sent-missing
- No issues found.

### Voice mode

#### voice (all phases)
- [x] S1 · (known) The "Voice mode" title, the ✕ button and the phase headline are plain `Text`/`Icon` with no Surface, so they take the default content colour (black). That is near-black on near-black in dark, and invisible in every dark render. Wrap the screen in LocusScreen/LocusTopBar · feature/chat/src/main/kotlin/dev/locus/feature/chat/voice/VoiceModeScreen.kt:257 · before/feature-chat/voice-idle_360-dark.png · fixed in #185 → after/feature-chat/voice-idle_360-dark.png
- [x] S2 · (known) Voice mode doesn't use LocusTopBar; it hand-builds a title row with a close IconButton · feature/chat/src/main/kotlin/dev/locus/feature/chat/voice/VoiceModeScreen.kt:255 · before/feature-chat/voice-idle_360-dark.png · fixed in #185 → after/feature-chat/voice-idle_360-dark.png
- [x] S2 · The privacy note "Speech is turned into t…" (on-device) and "No audio is …" (Google recognizer, even at 360) are cut by `maxLines = 2`. What happens to the user's voice is the part that gets lost. Let it wrap · feature/chat/src/main/kotlin/dev/locus/feature/chat/voice/VoiceModeScreen.kt:258 · before/feature-chat/voice-listening-system-recognizer_360-dark.png · fixed in #185 → after/feature-chat/voice-listening-system-recognizer_360-light-font200.png
- [x] S3 · (known) A decorative halo ring sits around the orb in every phase. Keep a single solid core that scales with the voice · core/designsystem/src/main/kotlin/dev/locus/core/designsystem/Voice.kt:56 · before/feature-chat/voice-failed_360-dark.png · fixed in #185 → after/feature-chat/voice-listening_360-light.png
- [x] S3 · The working phase uses a sparkle icon in the orb, a decorative AI tell. Use a neutral "working" glyph or a progress state · feature/chat/src/main/kotlin/dev/locus/feature/chat/voice/VoiceModeScreen.kt:322 · before/feature-chat/voice-working_360-dark.png · fixed in #185 → after/feature-chat/voice-working_360-dark.png

#### voice-failed
- [x] S2 · (known) The catch-all "Speech recognition failed. Tap to try again." says nothing about cause or fix. Every recognizer code should map to specific text and an action (for example code 5 client and 11 disconnected should say "Restart voice mode") · feature/chat/src/main/kotlin/dev/locus/feature/chat/voice/VoiceSession.kt:683 · before/feature-chat/voice-failed_360-dark.png · fixed in #185 → after/feature-chat/voice-failed-network_360-dark.png

#### voice-no-speech
- [x] S2 · (known) After a no-speech timeout the screen shows no message. The headline is invisible (see above) and there is no "Didn't catch that, tap to talk". Show a no-speech message instead of the idle state · feature/chat/src/main/kotlin/dev/locus/feature/chat/voice/VoiceSession.kt:392 · before/feature-chat/voice-no-speech_360-dark.png · fixed in #185 → after/feature-chat/voice-no-speech_360-dark.png

#### voice-approval
- [x] S2 · At 200% the approval card pushes the orb into a squashed pill under the "Microphone on" chip, and nothing scrolls. Let the content scroll and shrink the orb at large font · feature/chat/src/main/kotlin/dev/locus/feature/chat/voice/VoiceModeScreen.kt:286 · before/feature-chat/voice-approval_360-light-font200.png · fixed: the screen scrolls at a large font and the orb shrinks while an approval waits in #192 → after-open/feature-chat/voice-approval_360-light-font200.png

#### voice-tap-approval
- [x] S2 · The "Voice can't answer this one…" reason uses `caution` as text colour (4.35:1 on the light card). Use `onCautionContainer`. The reason is also repeated as the headline above the card · feature/chat/src/main/kotlin/dev/locus/feature/chat/voice/VoiceModeScreen.kt:345 · before/feature-chat/voice-tap-approval_360-light.png · fixed in #186 → after/feature-chat/voice-tap-approval_360-light.png

#### voice-failed-mic, voice-failed-network, voice-failed-audio, voice-failed-busy, voice-failed-language, voice-paused, voice-needs-screen
- [x] S3 · Each message ends with "Tap to try again" or "Tap to go on" as text, with no button. The mic one says "Allow it" but gives no "Open settings" action. Add a real action per reason · feature/chat/src/main/kotlin/dev/locus/feature/chat/voice/VoiceSession.kt:388 · before/feature-chat/voice-failed-mic_360-dark.png · fixed in #185 → after/feature-chat/voice-failed-mic_360-dark.png

#### voice-idle, voice-listening, voice-listening-system-recognizer, voice-speaking, voice-working
- No issues found beyond the voice (all phases) items. The guard's 2.96:1 and "Microphone on" 1.30:1 findings are false positives (transparent render).

## Settings

Looked at 360-dark, 360-light and 360-light-font200 of every screen (composites), plus 411 where needed. Paths are repo-relative to the worktree; renders relative to `renders/audit/`.

#### Shared components (cause issues on many Settings screens)
- [x] S1 · LocusSheet puts the primary action in the header row beside the title and subtitle; at 200% a long action ("Choose where to save", "Restore and restart", "Trust and save") crushes the title to one letter per line or "Re / s…" and pushes the form off screen; at 360 it already squeezes subtitles to 60% width. Stack the action under the title (or move it to the sheet's bottom) when it doesn't fit · core/designsystem/src/main/kotlin/dev/locus/core/designsystem/Components.kt:553 · before/feature-settings/settings-backup-password-sheet_360-light-font200.png · fixed in #186 → after/feature-settings/settings-backup-password-sheet_360-light-font200.png
- [x] S1 · Banner caps body text at 4 lines when actions aren't below, so critical warnings are cut mid-sentence ("Saved keys are replaced with …", "otherwise your key an…", the all-providers trust warning at 200%). Drop the cap for banners without side actions · core/designsystem/src/main/kotlin/dev/locus/core/designsystem/Components.kt:371 · before/feature-settings/settings-restore-preview_360-dark.png · fixed in #186 → after/feature-settings/settings-restore-preview_360-dark.png
- [x] S2 · SwitchRow caps the title at 2 lines and the subtitle at 4, so explanatory subtitles are cut even at 100% (Battery "…and scheduled…", Voice "Interrupt by speaking"), and at 200% titles are cut ("Pause background …", "Check GitHub for new vers…"). Let SwitchRow wrap fully and shorten long subtitles · core/designsystem/src/main/kotlin/dev/locus/core/designsystem/Controls.kt:63 · before/feature-settings/settings-battery_360-dark.png · fixed in #186 → after/feature-settings/settings-battery_360-dark.png
- [x] S2 · ListRow's titleMaxLines = 1 plus a trailing chip or text button cuts titles at 360 and makes them useless at 200% (Hub "Helper model …", Integrations "Firec…", Diagnostics "Crash in ChatViewM…"). Allow 2 lines for titles, and put trailing actions under the text at large font sizes · core/designsystem/src/main/kotlin/dev/locus/core/designsystem/Components.kt:212 · before/feature-settings/settings-integrations_360-light-font200.png · fixed in #186 → after/feature-settings/settings-integrations_360-light-font200.png
- [x] S2 · SectionLabel inside a LocusSheet keeps its 16dp start padding on top of the sheet's 24dp inset, so "Placeholders", "Extra headers", "Proxy" and "Trusted certificates" start at 40dp while cards, fields and notes start at 24dp. SectionNote has the same 16dp inset (known issue). Give both an inset parameter, or a sheet variant with 0 start padding · core/designsystem/src/main/kotlin/dev/locus/core/designsystem/Components.kt:276 · before/feature-settings/settings-provider-network-sheet_360-dark.png · fixed in #186 → after/feature-settings/settings-provider-network-sheet_360-dark.png
- [x] S2 · LocusCard's default contentPadding is Spacing.md (12dp), so text in a plain LocusCard starts 4dp left of the section label's 16dp (known issue: Agent → Task budget, Self-test summary, Storage summary, ProgressCard). Make the default horizontal padding Spacing.lg · core/designsystem/src/main/kotlin/dev/locus/core/designsystem/Components.kt:161 · before/feature-settings/settings-agent_360-dark.png · fixed in #186 → after/feature-settings/settings-agent_360-dark.png
- [x] S2 · Segmented labels are maxLines = 1, so at 200% the selected option can't be read ("Ask a…", "Med…", "Private…/Passw…", "No pro…"). Let it wrap or switch to a vertical radio list at large font sizes · core/designsystem/src/main/kotlin/dev/locus/core/designsystem/Components.kt:431 · before/feature-settings/settings-permissions_360-light-font200.png · fixed in #186 → after/feature-settings/settings-permissions_360-light-font200.png
- [x] S2 · ConfirmSheet's two equal-weight buttons break a long confirm label mid-word at 200% ("Trust everywh/ere") and wrap it at 360. Stack the buttons when the label doesn't fit · core/designsystem/src/main/kotlin/dev/locus/core/designsystem/Sheets.kt:91 · before/feature-settings/settings-network-certificate-confirm-all_360-light-font200.png · fixed in #186 → after/feature-settings/settings-network-certificate-confirm-all_360-light-font200.png
- [x] S3 · LocusIcons.Sparkle stands for six unrelated things (Models placeholder, Agent identity, Suggest follow-ups, helper fallback, on-device models in Storage, About's setup row, Permissions "Other"), which is slop decoration. Pick a specific icon for each or use none · feature/settings/src/main/kotlin/dev/locus/feature/settings/AgentSettings.kt:48 · before/feature-settings/settings-agent_360-dark.png · fixed in #189 → after/feature-settings/settings-agent_360-dark.png

#### Settings hub
- [x] S2 · The Models row shows the default provider's logo and "Anthropic · default · 5 …" next to a "No key" pill that's about another provider, so it reads as "Anthropic has no key". Name the provider in the pill or the summary ("Gemini: no key") · feature/settings/src/main/kotlin/dev/locus/feature/settings/SettingsHub.kt:52 · before/feature-settings/settings-hub_360-dark.png · fixed in #189 → after/feature-settings/settings-hub_360-dark.png
- [x] S2 · Every hub summary is cut at 360 ("Helper: OpenRouter · 3 of 3 extr…", "HTTP proxy · 2 certificates · 1 f…", "90 allowed · 35 ask first · Revie…"). Shorten the summaries to fit one line, or allow two · feature/settings/src/main/kotlin/dev/locus/feature/settings/SettingsHub.kt:130 · before/feature-settings/settings-hub_360-light.png · fixed in #189 → after/feature-settings/settings-hub_360-light.png
- [x] S2 · At 200% every row is cut to about 10 characters ("Anthro…", "Integrations & …", "Custom instruct…") · feature/settings/src/main/kotlin/dev/locus/feature/settings/SettingsHub.kt:130 · before/feature-settings/settings-hub_360-light-font200.png · fixed in #189 → after/feature-settings/settings-hub_360-light-font200.png
- [x] S3 · The section label repeats the row title ("Models" over "Models", "Agent" over "Agent"). Rename the sections or the rows · feature/settings/src/main/kotlin/dev/locus/feature/settings/SettingsHub.kt:37 · before/feature-settings/settings-hub_360-dark.png · fixed in #189 → after/feature-settings/settings-hub_360-dark.png
- [x] S3 · About & privacy uses a key icon, which says nothing about the page · feature/settings/src/main/kotlin/dev/locus/feature/settings/SettingsHub.kt:109 · before/feature-settings/settings-hub_360-dark.png · fixed in #189 → after/feature-settings/settings-hub_360-dark.png

#### Settings hub (fresh)
- [x] S2 · Permissions has an empty summary, so its title floats above a blank second line. Pass null or a real summary ("Every tool asks first") · feature/settings/src/main/kotlin/dev/locus/feature/settings/SettingsHub.kt:71 · before/feature-settings/settings-hub-fresh_360-dark.png · fixed in #189 → after/feature-settings/settings-hub-fresh_360-dark.png

#### Settings hub (loading)
- [x] S2 · While loading, Models and Permissions show blank lines but Helper ("same as each chat") and Network ("Direct") show default values that may be wrong once loaded. Show the same muted placeholder for every summary until the state has loaded · feature/settings/src/main/kotlin/dev/locus/feature/settings/SettingsHub.kt:59 · before/feature-settings/settings-hub-loading_360-dark.png · fixed in #189 → after/feature-settings/settings-hub-loading_360-dark.png

#### About & privacy
- [x] S2 · Privacy statements are cut at 3 lines ("Update check … with nothing about…" is cut even at 360; at 200% every statement loses its second half), so the privacy promise itself is lost. Let PrivacyRow wrap fully · feature/settings/src/main/kotlin/dev/locus/feature/settings/AboutSettings.kt:96 · before/feature-settings/settings-about_360-light-font200.png · fixed in #189 → after/feature-settings/settings-about_360-light-font200.png
- [x] S3 · "You can see every action Locus took in Activity" is plain text. Use DestinationLink to Activity · feature/settings/src/main/kotlin/dev/locus/feature/settings/AboutSettings.kt:52 · before/feature-settings/settings-about_360-dark.png · fixed in #192 → after-open/feature-settings/settings-about_360-dark.png
- [x] S3 · The same Regenerate icon is used for Update check, Check now and Run setup again, and Diagnostics borrows Battery's gauge · feature/settings/src/main/kotlin/dev/locus/feature/settings/AboutSettings.kt:61 · before/feature-settings/settings-about_360-dark.png · fixed in #189 → after/feature-settings/settings-about_360-dark.png

#### Updates (About → Updates: available, checking, downloading, ready, failed, no-network, off, skipped, up-to-date, from-store)
- [x] S3 · "Released 2026-10-28 on GitHub" is a whole sentence set in mono, with wide letter gaps. Only the date should be mono · feature/settings/src/main/kotlin/dev/locus/feature/settings/UpdatesSection.kt:154 · before/feature-settings/settings-updates-available_360-dark.png · fixed in #189 → after/feature-settings/settings-updates-available_360-dark.png
- [x] S3 · "Download and verify" is a wrap-width button while "Install update" in the same card is full width. Pick one · feature/settings/src/main/kotlin/dev/locus/feature/settings/UpdatesSection.kt:181 · before/feature-settings/settings-updates-ready_360-dark.png · fixed in #189 → after/feature-settings/settings-updates-available_360-dark.png
- [x] S3 · The release notes open with a "What's new" heading directly under the row "What's new in 0.2.0". Drop the first heading of the notes · feature/settings/src/main/kotlin/dev/locus/feature/settings/UpdatesSection.kt:195 · before/feature-settings/settings-updates-downloading_360-light.png · fixed in #189 → after/feature-settings/settings-updates-downloading_360-light.png
- [x] S3 · From store: the section label "Updates" sits over a row titled "Updates". Title the row by its content ("Updated by F-Droid") · feature/settings/src/main/kotlin/dev/locus/feature/settings/UpdatesSection.kt:71 · before/feature-settings/settings-updates-from-store_360-dark.png · fixed in #189 → after/feature-settings/settings-updates-from-store_360-dark.png

#### Diagnostics
- [x] S2 · Entry titles are cut at 360 ("Crash in ChatViewM…", "Stopped by Android: l…", "Provider error: OpenR…"), and the subtitle is a raw exception class ("java.lang.IllegalStateExc…"). Allow 2-line titles and say in words what went wrong. Keep the stack trace for the expanded view · feature/settings/src/main/kotlin/dev/locus/feature/settings/DiagnosticsSettings.kt:167 · before/feature-settings/settings-diagnostics_360-dark.png · fixed in #189 → after/feature-settings/settings-diagnostics_360-dark.png
- [x] S2 · Kind icons mean nothing: crash and provider error both use a code icon, a low-memory exit uses a stop square, and "Database grew" uses a key. Use one meaningful icon per kind, or a toned StatusDot · feature/settings/src/main/kotlin/dev/locus/feature/settings/DiagnosticsSettings.kt:163 · before/feature-settings/settings-diagnostics_360-dark.png · fixed in #189 → after/feature-settings/settings-diagnostics_360-dark.png
- [x] S3 · Clear and Share are loose TextButtons under the card. Make them a SectionLabel action or top-bar actions · feature/settings/src/main/kotlin/dev/locus/feature/settings/DiagnosticsSettings.kt:142 · before/feature-settings/settings-diagnostics_360-light.png · fixed in #189 → after/feature-settings/settings-diagnostics_360-light.png

#### Diagnostics (expanded)
- [x] S2 · The expanded trace is one unwrapped mono line clipped at the card edge, with no sign that it scrolls sideways; at 200% the subtitle breaks mid-word ("java.lan g.Illegal"). Wrap the trace, or add a fade and a Copy action · feature/settings/src/main/kotlin/dev/locus/feature/settings/DiagnosticsSettings.kt:170 · before/feature-settings/settings-diagnostics-expanded_360-dark.png · fixed in #189 → after/feature-settings/settings-diagnostics-expanded_360-dark.png

#### Diagnostics (empty)
- [x] S3 · The empty state is the note "Nothing recorded." with a trailing period. Use EmptyState ("No problems recorded") · feature/settings/src/main/kotlin/dev/locus/feature/settings/DiagnosticsSettings.kt:130 · before/feature-settings/settings-diagnostics-empty_360-dark.png · fixed in #189 → after/feature-settings/settings-diagnostics-empty_360-dark.png

#### Diagnostics → Clear confirm
- No issues found.

#### Self-test
- [x] S2 · At 200% the trailing fix buttons ("Free space", "Turn on") keep their full width and squeeze the check text into a 5-character column that breaks words ("Downlo/ads", "Notificatio/ns"). Put the action under the text · feature/settings/src/main/kotlin/dev/locus/feature/settings/SelfTestSettings.kt:349 · before/feature-settings/settings-self-test_360-light-font200.png · fixed in #189 → after/feature-settings/settings-self-test_360-light-font200.png
- [x] S3 · The "Failed" chip sits on the summary card's red glow and drops to 4.28:1 and 4.15:1 in dark (render guard; real). Remove the glow behind the chip or skip the glow on this card · feature/settings/src/main/kotlin/dev/locus/feature/settings/SelfTestSettings.kt:288 · before/feature-settings/settings-self-test_360-dark.png · fixed in #189 → after/feature-settings/settings-self-test_360-dark.png

#### Self-test (running)
- [x] S3 · "Checking…" is both the card title and the disabled button's label. Hide the button or label it "Stop" while running · feature/settings/src/main/kotlin/dev/locus/feature/settings/SelfTestSettings.kt:321 · before/feature-settings/settings-self-test-running_360-dark.png · fixed in #189 → after/feature-settings/settings-self-test-running_360-dark.png

#### Self-test (all pass)
- [x] S3 · The golden shows green ticks beside failure text ("Turned off: tasks can't tell you…", "Not installed", "need more"), because the fixture only flips the level. Give the pass fixture pass details · feature/settings/src/test/kotlin/dev/locus/feature/settings/SettingsScreenshots.kt:309 · before/feature-settings/settings-self-test-all-pass_360-dark.png · fixed in #189 → after/feature-settings/settings-self-test-all-pass_360-dark.png

#### Self-test (error)
- No issues found.

#### Storage
- [x] S2 · The orange bar under "Locus uses 4.4 GB" is phone storage used (86%), with no label, so it reads as "Locus is using too much". Label it ("Phone 86% full") and move it under the Phone line · feature/settings/src/main/kotlin/dev/locus/feature/settings/StorageSettings.kt:227 · before/feature-settings/settings-storage_360-dark.png · fixed in #189 → after/feature-settings/settings-storage_360-dark.png
- [x] S3 · "Couldn't measure" for Memory, skills and settings is plain mono text with no tone and no retry · feature/settings/src/main/kotlin/dev/locus/feature/settings/StorageSettings.kt:255 · before/feature-settings/settings-storage_360-light.png · fixed in #189 → after/feature-settings/settings-storage_360-light.png

#### Storage (clearing)
- No issues found.

#### Storage (error)
- [x] S2 · The ErrorState body is the raw exception "Permission denied: /data/user/0/dev.locus/files/linux". Say what couldn't be read in words and what to do; put the path in a details line · feature/settings/src/main/kotlin/dev/locus/feature/settings/StorageSettings.kt:191 · before/feature-settings/settings-storage-error_360-dark.png · fixed in #189 → after/feature-settings/settings-storage-error_360-dark.png

#### Storage (measuring)
- [x] S3 · The caption under the centred spinner is start-aligned in a SectionNote. Use LoadingState or centre the caption · feature/settings/src/main/kotlin/dev/locus/feature/settings/StorageSettings.kt:195 · before/feature-settings/settings-storage-measuring_360-dark.png · fixed in #189 → after/feature-settings/settings-storage-measuring_360-dark.png

#### Voice
- [x] S2 · "Interrupt by speaking" subtitle is cut at 4 lines ("…keeps hearing itself on the …"; known issue). Shorten it · feature/settings/src/main/kotlin/dev/locus/feature/settings/VoiceSettingsPage.kt:128 · before/feature-settings/settings-voice_360-dark.png · fixed in #185 → after/feature-settings/settings-voice_360-light.png
- [x] S2 · Its icon, LocusIcons.Stop, renders as a meaningless empty square (known issue). Use an icon that means interrupting speech, or none · feature/settings/src/main/kotlin/dev/locus/feature/settings/VoiceSettingsPage.kt:129 · before/feature-settings/settings-voice_360-dark.png · fixed in #185 → after/feature-settings/settings-voice_360-light.png
- [x] S3 · Engine row subtitle is cut ("its voices a…") · feature/settings/src/main/kotlin/dev/locus/feature/settings/VoiceSettingsPage.kt:95 · before/feature-settings/settings-voice_360-light.png · fixed in #192 → after-open/feature-settings/settings-voice_360-light.png

#### Voice (no recognizer)
- [x] S2 · The voice-mode switches stay enabled, and the only sign that voice mode can't work is the first sentence of a 7-line grey note. Disable the rows and show a Caution Banner with an action (open speech settings) · feature/settings/src/main/kotlin/dev/locus/feature/settings/VoiceSettingsPage.kt:138 · before/feature-settings/settings-voice-no-recognizer_360-light.png · fixed: the switches are off under a one-line Caution banner with "Speech settings" in #192 → after-open/feature-settings/settings-voice-no-recognizer_360-light.png

#### Voice (on-device / system recognizer)
- No issues found beyond the Voice items above.

#### Agent (Task budget)
- [x] S2 · "Tokens", "Estimated cost" and "Time" start 12dp in while the section label starts at 16dp (LocusCard padding, known issue) · feature/settings/src/main/kotlin/dev/locus/feature/settings/AgentSettings.kt:58 · before/feature-settings/settings-agent_360-dark.png · fixed in #189 → after/feature-settings/settings-agent_360-dark.png
- [x] S2 · Chip rows run off the card edge ("1M" and the time row cut mid-chip; at 200% "50" of "500k"), with no fade to show they scroll. Wrap the chips (FlowRow) · core/designsystem/src/main/kotlin/dev/locus/core/designsystem/TaskBudgetEditor.kt:77 · before/feature-settings/settings-agent_360-light-font200.png · fixed in #186 → after/feature-settings/settings-agent_360-dark.png
- [x] S2 · The cost row is disabled but "$2" is still drawn as selected, so it's unclear whether a $2 cap applies. Show the saved value as inactive and say it applies once prices are on · core/designsystem/src/main/kotlin/dev/locus/core/designsystem/TaskBudgetEditor.kt:57 · before/feature-settings/settings-agent_360-light.png · fixed in #186 → after/feature-settings/settings-agent_360-dark.png
- [x] S3 · The cost note ends "in Settings → Models." and a SettingsLink "Settings → Models" repeats it underneath. Cut the path from the sentence · core/designsystem/src/main/kotlin/dev/locus/core/designsystem/TaskBudgetEditor.kt:60 · before/feature-settings/settings-agent_360-dark.png · fixed in #186 → after/feature-settings/settings-agent_360-dark.png
- [x] S3 · Raw Material FilterChip instead of LocusChip(Filter) · core/designsystem/src/main/kotlin/dev/locus/core/designsystem/TaskBudgetEditor.kt:78 · before/feature-settings/settings-agent_360-dark.png · fixed in #186 → after/feature-settings/settings-agent_360-dark.png
- [x] S3 · The budget note is a 10-line paragraph under the card. Keep one sentence and move the rest behind a "How limits work" link · feature/settings/src/main/kotlin/dev/locus/feature/settings/AgentSettings.kt:68 · before/feature-settings/settings-agent_360-dark.png · fixed in #189 → after/feature-settings/settings-agent_360-dark.png

#### Agent (empty)
- No issues found beyond Agent's.

#### Permissions
- [x] S2 · Remembered rows show raw tool ids ("browser_open: github.com", "write_file in projects/invoice-parser/", "run_command: ./gradlew test …"). Say it in words ("Open github.com in the browser") · feature/settings/src/main/kotlin/dev/locus/feature/settings/PermissionsSettings.kt:114 · before/feature-settings/settings-permissions_360-dark.png · fixed in #189 → after/feature-settings/settings-permissions_360-dark.png
- [x] S2 · At 200% the safety-reviewer Segmented shows "Ask a…" and "All ch…" (shared Segmented issue) · feature/settings/src/main/kotlin/dev/locus/feature/settings/PermissionsSettings.kt:141 · before/feature-settings/settings-permissions_360-light-font200.png · fixed in #186 → after/feature-settings/settings-permissions_360-light-font200.png

#### Battery & heat
- [x] S2 · "Pause background extras" subtitle is cut at 4 lines even at 360 ("…refresh wait, and scheduled…"), so the list of what pauses is lost. Shorten it · feature/settings/src/main/kotlin/dev/locus/feature/settings/BatterySettings.kt:68 · before/feature-settings/settings-battery_360-dark.png · fixed in #189 → after/feature-settings/settings-battery_360-dark.png
- [x] S2 · Right now (hot): at 200% the 2-line cap drops "memory low"; at every size, hot and memory low get no tone. Use toned chips (Caution) for active conditions · feature/settings/src/main/kotlin/dev/locus/feature/settings/BatterySettings.kt:61 · before/feature-settings/settings-battery-hot_360-light-font200.png · fixed in #189 → after/feature-settings/settings-battery-hot_360-light-font200.png
- [x] S3 · The thermal explanation is a 9-line note · feature/settings/src/main/kotlin/dev/locus/feature/settings/BatterySettings.kt:86 · before/feature-settings/settings-battery_360-light.png · fixed in #189 → after/feature-settings/settings-battery_360-light.png

#### Automation
- [x] S2 · The note shows a repo path and a permission id to users ("docs/design/android-bridge.md", "dev.locus.permission.RUN_TASK"). Move them to a developer details row or a docs link · feature/settings/src/main/kotlin/dev/locus/feature/settings/AutomationSettings.kt:138 · before/feature-settings/settings-automation_360-dark.png · fixed in #189 → after/feature-settings/settings-automation_360-dark.png
- [x] S2 · App rows show raw package names ("Always allowed · net.ding…"). The label is enough; put the package in details · feature/settings/src/main/kotlin/dev/locus/feature/settings/AutomationSettings.kt:175 · before/feature-settings/settings-automation_360-dark.png · fixed in #189 → after/feature-settings/settings-automation_360-dark.png
- [x] S2 · "Default digital assist…" title is cut at 360 by the Change button · feature/settings/src/main/kotlin/dev/locus/feature/settings/AutomationSettings.kt:147 · before/feature-settings/settings-automation-off_360-light.png · fixed in #189 → after/feature-settings/settings-automation-off_360-light.png

#### Extras (Helper model & extras)
- [x] S3 · Toggle subtitles end with periods and the page opens with a long note; otherwise on-system · feature/settings/src/main/kotlin/dev/locus/feature/settings/ExtrasSettings.kt:59 · before/feature-settings/settings-extras_360-dark.png · fixed in #189 → after/feature-settings/settings-extras_360-dark.png

#### Extras (defaults)
- No issues found beyond Extras'.

#### Helper prompt sheet (default / custom / invalid / from page)
- [x] S2 · The "Placeholders" SectionLabel starts at 40dp while the text area and placeholder notes start at 24dp (shared sheet-inset issue) · feature/settings/src/main/kotlin/dev/locus/feature/settings/HelperPromptSheet.kt:92 · before/feature-settings/settings-helper-prompt-sheet-custom_360-dark.png · fixed in #189 → after/feature-settings/settings-helper-prompt-sheet-custom_360-dark.png
- [x] S2 · The Save button in the header squeezes the 3-line subtitle into about 60% of the width (shared LocusSheet header) · feature/settings/src/main/kotlin/dev/locus/feature/settings/HelperPromptSheet.kt:64 · before/feature-settings/settings-helper-prompt-sheet_360-dark.png · fixed in #186 → after/feature-settings/settings-helper-prompt-sheet_360-dark.png

#### Helper model sheet (+ same as chat)
- [x] S2 · At 200% option titles are cut ("Same as ea…", "Ollama on t…") · feature/settings/src/main/kotlin/dev/locus/feature/settings/ExtrasSettings.kt:149 · before/feature-settings/settings-helper-sheet_360-light-font200.png · fixed in #189 → after/feature-settings/settings-helper-sheet_360-light-font200.png

#### Models
- [x] S2 · Provider rows show the default model's vendor logo instead of the provider's (OpenRouter shows the DeepSeek whale, Ollama shows Qwen), so one provider appears with different icons on different screens. Badge the provider · feature/settings/src/main/kotlin/dev/locus/feature/settings/ModelsSettings.kt:119 · before/feature-settings/settings-models_360-dark.png · fixed in #189 → after/feature-settings/settings-models_360-dark.png
- [x] S2 · At 200% the Default chip squeezes provider titles to "claude-so…" and "Google G…" · feature/settings/src/main/kotlin/dev/locus/feature/settings/ModelsSettings.kt:125 · before/feature-settings/settings-models_360-light-font200.png · fixed in #189 → after/feature-settings/settings-models_360-light-font200.png

#### Models (empty)
- No issues found.

#### Models → Add provider
- [x] S2 · Preset notes are cut at 2 lines mid-sentence ("Downloads a 2.6 G…", "Enter the model ID fro…"). Allow full wrap or shorten the notes · feature/settings/src/main/kotlin/dev/locus/feature/settings/ModelsSettings.kt:390 · before/feature-settings/settings-models-add-provider_360-dark.png · fixed in #189 → after/feature-settings/settings-models-add-provider_360-dark.png

#### Models → Edit provider
- [x] S2 · At 200% the Effort Segmented reads "Med…" (shared Segmented issue) · feature/settings/src/main/kotlin/dev/locus/feature/settings/ModelsSettings.kt:542 · before/feature-settings/settings-models-edit-provider_360-light-font200.png · fixed in #186 → after/feature-settings/settings-models_360-light-font200.png
- [x] S3 · "Remove" (delete the provider) is a lone red TextButton at the sheet's bottom right. Use a full-width outlined critical action · feature/settings/src/main/kotlin/dev/locus/feature/settings/ModelsSettings.kt:551 · before/feature-settings/settings-models-edit-provider_360-dark.png · fixed in #189 → after/feature-settings/settings-models-edit-provider_360-dark.png

#### Models → Edit ChatGPT / ChatGPT account section
- [x] S2 · The account email is cut at 360 ("maya.okafor@fast…"), and at 200% "Sign out" wraps to two lines in the loose Manage usage / Sign out button row outside the card. Put the actions in the card as rows · feature/settings/src/main/kotlin/dev/locus/feature/settings/ChatGptSettings.kt:67 · before/feature-settings/settings-models-edit-chatgpt_360-light-font200.png · fixed in #189 → after/feature-settings/settings-models-edit-chatgpt_360-light-font200.png

#### ChatGPT signed in / signed out (section goldens)
- [x] S3 · The section is snapped without a Column, so its children are drawn on top of each other (text under the button, Manage usage over the email). All 13 render-guard contrast findings for these two shots ("Manage usage" 1.85, "Sign out" 2.28, "Signed in" 1.26, the description 2.01 and 2.96) come from that overlap: false positives. Wrap the snap in OnScreen/Column · feature/settings/src/test/kotlin/dev/locus/feature/settings/SettingsScreenshots.kt:420 · before/feature-settings/settings-chatgpt-signed-in_360-dark.png · fixed in #189 → after/feature-settings/settings-chatgpt-signed-in_360-dark.png

#### ChatGPT signing in
- No issues found.

#### ChatGPT sign-in failed / sign-out unconfirmed
- [x] S2 · The error is a bare red caption under the buttons, and the fallback "Sign-in failed." says nothing useful. Use a Critical Banner with the reason and Try again · feature/settings/src/main/kotlin/dev/locus/feature/settings/ChatGptSettings.kt:96 · before/feature-settings/settings-chatgpt-sign-in-failed_360-dark.png · fixed in #189 → after/feature-settings/settings-chatgpt-sign-in-failed_360-dark.png

#### ChatGPT welcome sheet
- [x] S2 · An information sheet with "Got it" in the header: at 200% the title is cut and the body is squeezed beside the button. Put the body in the content and the button at the bottom · feature/settings/src/main/kotlin/dev/locus/feature/settings/ChatGptSettings.kt:117 · before/feature-settings/settings-chatgpt-welcome_360-light-font200.png · fixed in #189 → after/feature-settings/settings-chatgpt-welcome_360-light-font200.png

#### ChatGPT sign-out confirm
- No issues found.

#### Network
- [x] S2 · "A provider can pick No proxy or its own under Network when you edit it in" ends on a dangling "in", uses a larger type than the notes around it, and starts at a different left edge, with the link on the next line. Rewrite it as one note plus the link · feature/settings/src/main/kotlin/dev/locus/feature/settings/NetworkSettings.kt:159 · before/feature-settings/settings-network_360-dark.png · fixed in #189 → after/feature-settings/settings-network_360-dark.png
- [x] S2 · "All providers" is a Critical (red) chip, but trusting a certificate is a choice, not an error. Use Caution · feature/settings/src/main/kotlin/dev/locus/feature/settings/NetworkSettings.kt:184 · before/feature-settings/settings-network_360-light.png · fixed in #189 → after/feature-settings/settings-network_360-light.png
- [x] S3 · The SettingsLink renders disabled (faded) in every render because the test doesn't provide LocalSettingsLinks. Provide links in the harness so the golden shows the real state · feature/settings/src/test/kotlin/dev/locus/feature/settings/SettingsScreenshots.kt:197 · before/feature-settings/settings-network-empty_360-light.png · fixed in #189 → after/feature-settings/settings-network-empty_360-light.png

#### Network (import failed)
- [x] S3 · The Critical banner butts against the next card with no gap, and it doesn't say what to do ("Export the CA as .pem or .crt") · feature/settings/src/main/kotlin/dev/locus/feature/settings/NetworkSettings.kt:169 · before/feature-settings/settings-network-import-failed_360-dark.png · fixed in #189 → after/feature-settings/settings-network-import-failed_360-dark.png

#### Network → Certificate sheet (+ all providers)
- [x] S2 · An expired certificate ("Expired October 9, 2025") that every provider trusts gets no caution tone or banner in the sheet · feature/settings/src/main/kotlin/dev/locus/feature/settings/NetworkSettings.kt:296 · before/feature-settings/settings-network-certificate-sheet-all-providers_360-dark.png · fixed in #189 → after/feature-settings/settings-network-certificate-sheet-all-providers_360-dark.png
- [x] S3 · The fingerprint wraps mid-byte (":70…" on a new line). Break only at colons, or group the bytes · feature/settings/src/main/kotlin/dev/locus/feature/settings/NetworkSettings.kt:296 · before/feature-settings/settings-network-certificate-sheet_360-light.png · fixed in #189 → after/feature-settings/settings-network-certificate-sheet_360-light.png

#### Network → Confirm trust all / confirm remove
- [x] S2 · "Trust everywhere" wraps at 360 and breaks mid-word at 200% (shared ConfirmSheet issue) · feature/settings/src/main/kotlin/dev/locus/feature/settings/NetworkSettings.kt:310 · before/feature-settings/settings-network-certificate-confirm-all_360-light-font200.png · fixed in #189 → after/feature-settings/settings-network-certificate-confirm-all_360-light-font200.png

#### Network → Proxy sheet (+ none)
- [x] S2 · At 200% Host and Port values are clipped in their fields ("proxy.northwi", "312("). Stack the fields at large font sizes · feature/settings/src/main/kotlin/dev/locus/feature/settings/ProviderNetworkEditor.kt:336 · before/feature-settings/settings-network-proxy-sheet_360-light-font200.png · fixed in #189 → after/feature-settings/settings-network-proxy-sheet_360-light-font200.png

#### Provider network sheet (+ custom proxy)
- [x] S2 · "Change it in Settings → Network." and "Import a CA certificate in Settings → Network…" are plain text. Use SettingsLink · feature/settings/src/main/kotlin/dev/locus/feature/settings/ProviderNetworkEditor.kt:201 · before/feature-settings/settings-provider-network-sheet_360-dark.png · fixed in #192 → after-open/feature-settings/settings-provider-network-sheet_360-dark.png
- [x] S2 · Certificate rows show the raw fingerprint as the subtitle ("SHA-256 3A:9F:12:C4:7B:E0:55:8…"). Show the expiry or who trusts it; keep the hash in the certificate sheet · feature/settings/src/main/kotlin/dev/locus/feature/settings/ProviderNetworkEditor.kt:231 · before/feature-settings/settings-provider-network-sheet_360-dark.png · fixed in #189 → after/feature-settings/settings-provider-network-sheet_360-dark.png
- [x] S2 · Custom proxy: the validation message "Allow replacing the key header, or remove that header." is a red line at the bottom of the sheet, far from the header row and the Done button. Show it on the offending row or as a Banner at the top · feature/settings/src/main/kotlin/dev/locus/feature/settings/ProviderNetworkEditor.kt:79 · before/feature-settings/settings-provider-network-sheet-custom-proxy_360-dark.png · fixed in #189 → after/feature-settings/settings-provider-network-sheet-custom-proxy_360-dark.png
- [x] S3 · X-Title shows a "No value" chip and a "No value yet" subtitle. Keep one · feature/settings/src/main/kotlin/dev/locus/feature/settings/ProviderNetworkEditor.kt:160 · before/feature-settings/settings-provider-network-sheet_360-light.png · fixed in #189 → after/feature-settings/settings-provider-network-sheet_360-light.png

#### Add header sheet (+ filled, duplicate, forbidden, credential)
- [x] S2 · The Authorization warning is drawn in raw caution colour: #B0680A on #F7F6F2 is 4.02:1 in light (render guard; real). Use Tone.Caution.onContainer(), or a Caution Banner · feature/settings/src/main/kotlin/dev/locus/feature/settings/ProviderNetworkEditor.kt:285 · before/feature-settings/settings-add-header-sheet-credential_360-light.png · fixed in #189 → after/feature-settings/settings-add-header-sheet-credential_360-light.png
- [x] S3 · The warning says "Only turn this on…" but the switch it means isn't in this sheet. Say where it is · feature/settings/src/main/kotlin/dev/locus/feature/settings/ProviderNetworkEditor.kt:177 · before/feature-settings/settings-add-header-sheet-credential_360-dark.png · fixed in #189 → after/feature-settings/settings-add-header-sheet-credential_360-dark.png

#### Integrations (+ none)
- [x] S2 · "Official MCP server available" is primary-coloured supporting text, so it looks like a link but isn't tappable. It wraps to two lines on some rows, making row heights uneven, and at 200% breaks mid-word ("availabl e"). Make it a neutral chip or move it into the sheet · feature/settings/src/main/kotlin/dev/locus/feature/settings/IntegrationsSettings.kt:135 · before/feature-settings/settings-integrations_360-dark.png · fixed in #189 → after/feature-settings/settings-integrations_360-dark.png
- [x] S2 · At 360 the summaries are cut by the trailing chip ("Web search and page …"); at 200% titles are cut to "Firec…" and "GitH…" · feature/settings/src/main/kotlin/dev/locus/feature/settings/IntegrationsSettings.kt:73 · before/feature-settings/settings-integrations_360-light-font200.png · fixed in #189 → after/feature-settings/settings-integrations_360-light-font200.png

#### Integration key sheet (+ MCP, saved, remove)
- [x] S2 · At 200% "Remove key" breaks mid-word ("Remov e key") in the button row · feature/settings/src/main/kotlin/dev/locus/feature/settings/IntegrationsSettings.kt:243 · before/feature-settings/settings-integration-key-sheet-mcp_360-light-font200.png · fixed in #189 → after/feature-settings/settings-integration-key-sheet-mcp_360-light-font200.png
- [x] S3 · The header subtitle repeats the first sentence of the body ("Web search built for AI agents"), and the MCP card's copy is off ("…or use api key", "Needs personal access token") · feature/settings/src/main/kotlin/dev/locus/feature/settings/IntegrationsSettings.kt:181 · before/feature-settings/settings-integration-key-sheet_360-dark.png · fixed in #189 → after/feature-settings/settings-integration-key-sheet_360-dark.png

#### Telegram sheet
- [x] S2 · At 200% "Open @BotFather" wraps to three lines ("@BotF / ather") beside "Find my chat". Stack the two actions · core/ui/src/main/kotlin/dev/locus/core/ui/TelegramSheet.kt:192 · before/feature-settings/settings-telegram-sheet_360-light-font200.png · fixed: the actions wrap (#189) and "/newbot" no longer breaks in #192 → after-open/feature-settings/settings-telegram-sheet_360-light-font200.png

#### SSH machines (+ empty)
- [x] S2 · The note names a raw tool id and a page in plain text ("unless you change ssh_exec under Permissions"). Say "running commands" and use SettingsLink(Permissions) · feature/settings/src/main/kotlin/dev/locus/feature/settings/SshSettings.kt:70 · before/feature-settings/settings-ssh-empty_360-dark.png · fixed in #189 → after/feature-settings/settings-ssh-empty_360-dark.png

#### SSH add / edit sheet
- [x] S1 · At 200% "Trust and save" in the header crushes the title and the 4-line subtitle into a one-word column that breaks words ("machin/e's", "afterwa/rds") (shared LocusSheet header) · feature/settings/src/main/kotlin/dev/locus/feature/settings/SshSettings.kt:113 · before/feature-settings/settings-ssh-add_360-light-font200.png · fixed in #189 → after/feature-settings/settings-ssh-add_360-light-font200.png
- [x] S3 · "Host key: SHA256:…" is an unlabelled run-on caption that wraps mid-hash. Use a labelled mono KeyValueRow · feature/settings/src/main/kotlin/dev/locus/feature/settings/SshSettings.kt:137 · before/feature-settings/settings-ssh-edit_360-dark.png · fixed in #189 → after/feature-settings/settings-ssh-edit_360-dark.png

#### Backup & restore
- [x] S3 · "Back up now" subtitle is cut ("About 46.1 MB before compres…", "Chats, settings, memory, skills, …") · feature/settings/src/main/kotlin/dev/locus/feature/settings/BackupSettings.kt:134 · before/feature-settings/settings-backup_360-dark.png · fixed in #189 → after/feature-settings/settings-backup_360-dark.png

#### Backup (running / run active)
- [x] S2 · While a backup runs, "Back up now" and "Restore from a backup" keep full colour and chevrons but do nothing (onClick = null). Pass enabled = false · feature/settings/src/main/kotlin/dev/locus/feature/settings/BackupSettings.kt:136 · before/feature-settings/settings-backup-running_360-dark.png · fixed in #189 → after/feature-settings/settings-backup-running_360-dark.png

#### Backup (estimating)
- [x] S3 · Nothing says the size is being worked out; the rows show their generic copy. Add "Working out the size…" · feature/settings/src/main/kotlin/dev/locus/feature/settings/BackupSettings.kt:134 · before/feature-settings/settings-backup-estimating_360-light.png · fixed in #192 → after-open/feature-settings/settings-backup-estimating_360-light.png

#### Backup (failed)
- [x] S2 · Shows the raw exception ("Backup failed: No space left on device", or a class name) and "delete it" without naming the file or offering a way to do it. Map errors to plain words plus an action (Free space, Try again) · feature/settings/src/main/kotlin/dev/locus/feature/settings/BackupViewModel.kt:90 · before/feature-settings/settings-backup-failed_360-dark.png · fixed in #189 → after/feature-settings/settings-backup-failed_360-dark.png

#### Backup (done / with keys)
- No issues found.

#### Backup password sheet (+ mismatch, short)
- [x] S1 · At 200% the header button "Choose where to save" leaves the title and subtitle one letter per line ("B / . / P / r / o…"), pushing both fields off screen (shared LocusSheet header) · feature/settings/src/main/kotlin/dev/locus/feature/settings/BackupSettings.kt:203 · before/feature-settings/settings-backup-password-sheet-mismatch_360-light-font200.png · fixed in #189 → after/feature-settings/settings-backup-password-sheet-mismatch_360-light-font200.png
- [x] S3 · "If you forget this password … Nobody can recover it" is a Neutral banner, though it's the one irreversible fact here. Use Caution · feature/settings/src/main/kotlin/dev/locus/feature/settings/BackupSettings.kt:230 · before/feature-settings/settings-backup-password-sheet_360-dark.png · fixed in #189 → after/feature-settings/settings-backup-password-sheet_360-dark.png

#### Restore → password / wrong password
- No issues found beyond the shared header squeeze.

#### Restore → not a backup
- [x] S2 · Dead end: the sheet keeps "Enter the password it was saved with" and a disabled Check, but has no field and no "Choose another file". Show an error state with that action · feature/settings/src/main/kotlin/dev/locus/feature/settings/BackupSettings.kt:258 · before/feature-settings/settings-restore-not-a-backup_360-dark.png · fixed in #189 → after/feature-settings/settings-restore-not-a-backup_360-dark.png

#### Restore → verifying / restoring
- [x] S3 · The progress label repeats the sheet title ("Checking the backup · 383 MB of 1.2 GB" under "Checking the backup") · feature/settings/src/main/kotlin/dev/locus/feature/settings/BackupSettings.kt:282 · before/feature-settings/settings-restore-verifying_360-light.png · fixed in #189 → after/feature-settings/settings-restore-verifying_360-light.png

#### Restore → preview (+ run active)
- [x] S1 · The Critical banner explaining what gets replaced is cut ("Saved keys are replaced with …", "Keys saved on this phone are k…"), so the user can't read what happens to their keys before confirming (shared Banner cap) · feature/settings/src/main/kotlin/dev/locus/feature/settings/BackupSettings.kt:296 · before/feature-settings/settings-restore-preview_360-dark.png · fixed in #189 → after/feature-settings/settings-restore-preview_360-dark.png
- [x] S1 · At 200% the title is crushed to "Re / s…" by the header's "Restore and restart" (shared LocusSheet header) · feature/settings/src/main/kotlin/dev/locus/feature/settings/BackupSettings.kt:285 · before/feature-settings/settings-restore-preview_360-light-font200.png · fixed in #189 → after/feature-settings/settings-restore-preview_360-light-font200.png
- [x] S2 · The destructive action sits at the top, before the warning, which is at the bottom. Put the warning first and the action after it · feature/settings/src/main/kotlin/dev/locus/feature/settings/BackupSettings.kt:285 · before/feature-settings/settings-restore-preview_360-light.png · fixed in #189 → after/feature-settings/settings-restore-preview_360-light.png
- [x] S3 · "database v17" (internal schema version) is shown in the manifest subtitle · feature/settings/src/main/kotlin/dev/locus/feature/settings/BackupSettings.kt:312 · before/feature-settings/settings-restore-preview_360-dark.png · fixed in #189 → after/feature-settings/settings-restore-preview_360-dark.png

#### Restore → preview, no space
- [x] S1 · "Restore and restart" stays enabled while the banner says there isn't enough space (actionEnabled only checks runActive). Disable it, and say how much space is needed · feature/settings/src/main/kotlin/dev/locus/feature/settings/BackupSettings.kt:289 · before/feature-settings/settings-restore-preview-no-space_360-dark.png · fixed: Restore and restart is off; the banner gives what it needs, what is free and what to free, with a Storage link in #192 → after-open/feature-settings/settings-restore-preview-no-space_360-dark.png

## Library, onboarding, workspace, tasks, activity, insights, browser, core/ui, markdown and the design system

Renders read as dark | light | light-font200 contact sheets at 360dp. Source refs are to worktree agent-ab8b1e4868f63b5df. Severity: S1 broken/misleading/content lost, S2 off-system, S3 polish.

### Design-system components (root causes that show up on many screens)

#### LocusCard / CardGroup / KeyValueRow / SectionLabel / SectionNote alignment
- [x] S2 · LocusCard's default contentPadding is Spacing.md (12dp), so card text starts 4dp left of SectionLabel/ListRow text (16dp) and gives every card screen two left edges (known: Agent → Task budget; also Linux card, Insights cards, Linux health cards) · fix: default contentPadding to Spacing.lg horizontally · core/designsystem/src/main/kotlin/dev/locus/core/designsystem/Components.kt:161 · before/core-designsystem/cards_360-dark.png · fixed in #186 → after/feature-workspace/linux-health_360-dark.png
- [x] S2 · Dense KeyValueRow pads Spacing.md horizontally, so inside one CardGroup "Status" starts at 12dp while the "Model" row and ListRows start at 16dp (two edges in one card); inside a LocusCard it nests to 24dp (Linux card Version/Space used, Linux health Linux uses/Phone memory are visibly indented past the card title) · fix: dense rows use the parent's inset, no own horizontal padding · core/designsystem/src/main/kotlin/dev/locus/core/designsystem/Controls.kt:147 · before/core-designsystem/cards_360-dark.png · fixed: dense rows have no inset of their own in #192 → after-open/core-designsystem/cards_360-dark.png
- [x] S2 · SectionNote always adds start = Spacing.lg; inside a LocusSheet (24dp inset) notes start at 40dp while fields, title and cards start at 24dp (known; visible in every workflow sheet: "Asks you before…", "Runs on its own with…", "Inputs go in braces", empty picker) · fix: SectionNote takes the container's inset (0 inside sheets) or sheets use SupportingText · core/designsystem/src/main/kotlin/dev/locus/core/designsystem/Components.kt:296 · before/core-ui/workflow-run-error_360-dark.png · fixed in #186 → after/core-ui/workflow-run-error_360-dark.png
- [x] S2 · Dense KeyValueRow label is a fixed 32% column with maxLines 1, so paths and longer labels are cut to meaningless stubs ("/usr/lib/nod…", "/usr/lib/pyth…", "Phone mem…", "Linux progra…", at 200% "Versi…", "Spac…", "Servi…", "open…") · fix: let the label wrap (maxLines 2) or switch to a stacked layout when it doesn't fit; paths ellipsize in the middle · core/designsystem/src/main/kotlin/dev/locus/core/designsystem/Controls.kt:152 · before/feature-workspace/linux-health_360-dark.png · fixed in #186 → after/feature-workspace/linux-health_360-dark.png

#### ListRow / ExpandableRow
- [x] S1 · ExpandableRow's meta text has no weight or maxLines, so a long meta ("5 hours ago · after a task") takes the row width and the title column collapses to zero: at 200% the memory notes show only their timestamps, the title and summary are gone · fix: cap meta width (maxLines 1, widthIn max ~40%) or move meta under the subtitle · core/designsystem/src/main/kotlin/dev/locus/core/designsystem/Rows.kt:100 · before/feature-library/library-memory_360-light-font200.png · fixed in #186 → after/feature-library/library-memory_360-light-font200.png
- [x] S2 · Same at 360dp 100%: titles are cut to "travel pref…", "accou…", "home netw…" because the meta is wider than the title column · fix: as above · core/designsystem/src/main/kotlin/dev/locus/core/designsystem/Rows.kt:100 · before/feature-library/library-memory_360-dark.png · fixed in #186 → after/feature-library/library-memory_360-dark.png
- [x] S2 · ListRow's trailing slot (chips, "Restore" + ✕, actions) is unconstrained, so at 200% titles and subtitles squeeze to one syllable per line or "Ho…", "Brav…", "GitH…", "inv…", "bud…" (Library connectors/skills/local MCP, onboarding connectors, Trash, Linux health checks) · fix: give trailing a max width share, or stack trailing under the text when the row is narrow · core/designsystem/src/main/kotlin/dev/locus/core/designsystem/Components.kt:247 · before/feature-library/library-connectors_360-light-font200.png · fixed in #186 → after/feature-workspace/linux-health-broken_360-light-font200.png

#### Banner
- [x] S1 · Banner caps body text at 4 lines whenever actionsBelow is false, even with no actions, so warnings lose their point: Library's "Review before approving" ends "…when it uses the skill, and an…" (the sentence about scripts running in Linux is cut), the browser hand-off ends "it shows the p…" at 200% · fix: only cap when actions sit on the right, and default to actionsBelow when text exceeds 2 lines · core/designsystem/src/main/kotlin/dev/locus/core/designsystem/Components.kt:371 · before/feature-library/library-skills_360-dark.png · fixed in #186 → after/feature-library/library-skills_360-dark.png

#### Segmented
- [x] S2 · Segmented labels are maxLines 1 in equal-width slots, so at 200% they truncate to "Mem… Skill… Conn… Help…", "Privat… Passw…", "Check … Balanc… Indepe…"; even at 360dp "Connectors · 1" shows "Connector…" · fix: let labels wrap to 2 lines or switch to a scrollable tab row when they don't fit · core/designsystem/src/main/kotlin/dev/locus/core/designsystem/Components.kt:431 · before/feature-library/library-memory_360-light-font200.png · fixed in #186 → after/feature-library/library-memory_360-light-font200.png

#### LocusSheet header
- [x] S2 · The sheet action button sits beside the title and subtitle, so at 200% the subtitle becomes a one-word column ("Read / what it / needs…") and titles cut to "Dep lo…", "Import “invoi…", "Monthly invoice …" · fix: put the action under the subtitle (or in a bottom bar) when the title block is narrower than ~60% · core/designsystem/src/main/kotlin/dev/locus/core/designsystem/Components.kt:553 · before/core-ui/workflow-run-error_360-light-font200.png · fixed in #186 → after/core-ui/workflow-run-error_360-light-font200.png

#### SwitchRow
- [x] S1 · SwitchRow caps the subtitle at 4 lines, which cuts disclosures: onboarding's "Check GitHub for new versions?" loses "this bypasses app-store checks. Change it in Settings → About & privacy" (also the known Settings → Voice "Interrupt by speaking" truncation) · fix: no line cap on SwitchRow subtitles, or shorten the copy and move detail to a note · core/designsystem/src/main/kotlin/dev/locus/core/designsystem/Controls.kt:64 · before/feature-onboarding/onboarding-privacy_360-dark.png · fixed in #186 → after/feature-onboarding/onboarding-privacy_360-dark.png

#### StatusLine / tone text
- [x] S2 · StatusLine draws text in tone.color(), which is below AA on the light background: "10 actions today · 1 failed" #B0680A on #F7F6F2 4.02:1, "1 running"/"2 running" #1E8E50 (render guard) · fix: use tone.onContainer() for text in light theme · core/designsystem/src/main/kotlin/dev/locus/core/designsystem/Components.kt:342 · before/feature-activity/activity_360-light.png · fixed in #186 → after/feature-activity/activity_360-light.png
- [x] S3 · Diff signs and counts: "+"/"-" #1E8E50 on #D1DDD3 and #C4352B on #E5D2CE, "+42" #1E8E50 on white, below AA in light (render guard) · fix: on-container shades for the sign column and counts · core/designsystem/src/main/kotlin/dev/locus/core/designsystem/Diff.kt:87 · before/core-designsystem/diff_360-light.png · fixed in #186 → after/core-designsystem/diff_360-light.png

#### Voice orb
- [x] S2 · Known (voice mode, dark): title, ✕ and headline are near-black on near-black because the screen is a bare Column, not LocusScreen/LocusTopBar · fix: LocusScreen with LocusTopBar("Voice mode", subtitle) · feature/chat/src/main/kotlin/dev/locus/feature/chat/voice/VoiceModeScreen.kt:254 · before/core-designsystem/voice-orb_360-dark.png · fixed in #185 → after/feature-chat/voice-idle_360-dark.png
- [x] S1 · Known: "Speech recognition failed." is the catch-all for every recognizer error including no speech heard; it says nothing about what to do · fix: map NO_MATCH/SPEECH_TIMEOUT to "Didn't catch that. Tap and speak again", busy/permission/language to their own text and action · feature/chat/src/main/kotlin/dev/locus/feature/chat/voice/VoiceSession.kt:683 · before/core-designsystem/voice-orb_360-dark.png · fixed in #185 → after/feature-chat/voice-nothing-heard_360-dark.png
- [x] S2 · Known: the static halo ring around the orb reads as decoration when nothing is being heard; when the orb is squeezed (the preview's second orb) clip(CircleShape) on a non-square box turns the halo into a stadium · fix: show the halo only while level > 0, and give the orb a fixed square size (requiredSize) · core/designsystem/src/main/kotlin/dev/locus/core/designsystem/Voice.kt:56 · before/core-designsystem/voice-orb_360-light.png · fixed in #185 → after/core-designsystem/voice-orb_360-light.png

#### Other components
- [x] S3 · The "No authentication" / sheet / banners / chips / tones / meters / top-bar / states / feedback / search-field / code-surface / charts / composer-parts / confirm-sheet renders show no further issues beyond the rows above. · no change needed: a note that these renders had no further issues in #192 → after-open/core-designsystem/states_360-dark.png
- [x] S3 · render guard "◉" white on #57AEA5 in icons is the on-device ProviderBadge monogram (owned by #179); skipped. · resolved: the on-device badge is now a phone IconTile and draws no monogram in #192 → after-open/core-designsystem/icons_360-light.png

### feature-library

#### Library (all tabs)
- [x] S2 · Tab label "Connectors · 1" truncates to "Connector…" at 360dp 100%, and the pending tint draws the selected label in caution #B0680A on white (3.98:1, render guard "Skills · 1", "Connectors · 1", "Helpers · 1") · fix: shorter labels or a count chip, and tint with Tone.Caution.onContainer() · feature/library/src/main/kotlin/dev/locus/feature/library/LibraryScreen.kt:104 · before/feature-library/library-connectors_360-light.png · fixed in #187 → after/feature-library/library-connectors_360-light.png
- [x] S2 · Tab intro notes are SectionNote + padding(start = xs), so they start at 36dp while section labels start at 32dp and cards at 16dp (three edges) · fix: drop the extra xs, use SectionLabel + SectionNote at one inset · feature/library/src/main/kotlin/dev/locus/feature/library/LibraryScreen.kt:173 · before/feature-library/library-memory_360-dark.png · fixed in #187 → after/feature-library/library-memory_360-dark.png
- [x] S2 · Library uses a raw Scaffold with its own top bar column instead of LocusScreen · fix: LocusScreen(topBar = LocusTopBar + Segmented) · feature/library/src/main/kotlin/dev/locus/feature/library/LibraryScreen.kt:85 · before/feature-library/library-skills_360-dark.png · fixed in #187 → after/feature-library/library-skills_360-dark.png

#### Library → Skills
- [x] S1 · "Review before approving" banner is cut at 4 lines, hiding "any scripts in it run in the Linux sandbox" (see Banner) · fix: actionsBelow = true / no cap · feature/library/src/main/kotlin/dev/locus/feature/library/SkillsTab.kt:204 · before/feature-library/library-skills_360-dark.png · fixed in #187 → after/feature-library/library-skills_360-dark.png
- [x] S2 · Caution text drawn in Locus.colors.caution on the card: "Linux commands aren't limited to the hosts listed…", "Not pinned to an exact version…", script file names, 3.98:1 on white (render guard, many screenshots) · fix: Tone.Caution.onContainer() or put the warnings in a caution Banner · feature/library/src/main/kotlin/dev/locus/feature/library/SkillsTab.kt:434 · before/feature-library/library-skills_360-light.png · fixed in #187 → after/feature-library/library-skills_360-light.png
- [x] S2 · Script file "scripts/extract.py" is caution-coloured mono text with no affordance; it is tappable but reads as a warning, and 3.98:1 · fix: ListRow with a file icon, chevron and a "script" chip · feature/library/src/main/kotlin/dev/locus/feature/library/SkillsTab.kt:240 · before/feature-library/library-skills_360-light.png · fixed in #187 → after/feature-library/library-skills_360-light.png
- [x] S3 · Workflows and Skills sections have no SectionLabel; the intro notes act as headers · fix: SectionLabel("Workflows")/("Skills") with the note under it · feature/library/src/main/kotlin/dev/locus/feature/library/SkillsTab.kt:94 · before/feature-library/library-skills_360-dark.png · fixed in #187 → after/feature-library/library-skills_360-dark.png
- [x] S3 · Expanded skill content ("Declares:", "What it can do") starts at the card edge while the row's title starts after the icon: two edges in one card · fix: indent the expanded block to the title column or drop the leading tile width · feature/library/src/main/kotlin/dev/locus/feature/library/SkillsTab.kt:189 · before/feature-library/library-skills_360-dark.png · fixed in ExpandableRow: content starts under the title in #192 → after-open/feature-library/library-skills_360-dark.png

#### Library → Connectors / local MCP
- [x] S2 · Env line "BRAVE_API_KEY = vault “brave-search-brave-api-key” (not added yet)" shows the raw vault key and is caution text at 3.98:1 · fix: "Brave API key: not added" with the Add action, key id only in a mono details row · feature/library/src/main/kotlin/dev/locus/feature/library/LocalMcpUi.kt:173 · before/feature-library/library-local-mcp-states_360-light.png · fixed in #187 → after/feature-library/library-local-mcp-states_360-light.png
- [x] S2 · At 200% the connector rows squeeze to "Ho… / ha.o / kafor / .dev" and "Bra… / Loca / l / MCP" (ExpandableRow badge has no width cap) · fix: see ListRow/ExpandableRow · feature/library/src/main/kotlin/dev/locus/feature/library/LocalMcpUi.kt:153 · before/feature-library/library-connectors_360-light-font200.png · fixed in #186 → after/feature-library/library-connectors_360-light-font200.png

#### Library → Memory
- [x] S1 · At 200% note titles disappear; rows show only "5 hours ago · after a task" (ExpandableRow meta) · fix: see ExpandableRow · feature/library/src/main/kotlin/dev/locus/feature/library/LibraryScreen.kt:259 · before/feature-library/library-memory_360-light-font200.png · fixed in #187 → after/feature-library/library-memory_360-light-font200.png
- [x] S3 · Meta tag "check" is unexplained jargon after the time ("11 hours ago · by Locus · check") · fix: a caution chip "Needs a look" · feature/library/src/main/kotlin/dev/locus/feature/library/MemoryOrigin.kt:38 · before/feature-library/library-memory_360-dark.png · fixed in #187 → after/feature-library/library-memory_360-dark.png

#### Import review sheet
- [x] S2 · At 200% the header subtitle is a one-word column beside "Save as draft" and the title is "Import “invoi…" (LocusSheet header) · fix: see LocusSheet · core/designsystem/src/main/kotlin/dev/locus/core/designsystem/Components.kt:553 · before/feature-library/library-sheet-import-review_360-light-font200.png · fixed in #186 → after/feature-library/library-sheet-import-review_360-light-font200.png
- [x] S3 · Render guard raw-identifier "sha256:1ecd…" is a false positive: it sits in a labelled "Content hash" mono details row, which the design system allows · fix: none (consider baseline-listing it) · feature/library/src/main/kotlin/dev/locus/feature/library/SkillsTab.kt:440 · before/feature-library/library-sheet-import-review_360-dark.png · no change: a labelled hash is allowed, and it stays listed in feature/library's render-guard baseline in #192 → after-open/feature-library/library-sheet-import-review_360-dark.png

#### Workflow sheets (run / schedule / edit, shared core-ui)
- [x] S2 · "Asks you before: write_file." and "Runs on its own with: list_files, read_file, run_command" print raw tool ids in body text · fix: use toolLabel()/plain names ("write files", "run commands") · core/ui/src/main/kotlin/dev/locus/core/ui/WorkflowSheets.kt:276 · before/feature-library/library-sheet-workflow-schedule_360-dark.png · fixed in #187 → after/feature-library/library-sheet-workflow-schedule_360-dark.png
- [x] S2 · Same notes start at 40dp while fields start at 24dp (SectionNote in sheet, known) · fix: SupportingText at the sheet inset · core/ui/src/main/kotlin/dev/locus/core/ui/WorkflowSheets.kt:279 · before/core-ui/workflow-run-error_360-dark.png · fixed in #187 → after/core-ui/workflow-run-error_360-dark.png
- [x] S2 · "Scheduled runs are read-only and nobody is there to approve…" is caution text on the sheet surface below AA (render guard) · fix: Tone.Caution.onContainer() or a caution Banner · feature/library/src/main/kotlin/dev/locus/feature/library/LibraryWorkflows.kt:268 · before/feature-library/library-sheet-workflow-schedule_360-light.png · fixed in #187 → after/feature-library/library-sheet-workflow-schedule_360-light.png

### feature-onboarding

#### All steps
- [x] S2 · Three left edges: StepHeading pads start = xs (20dp), the footer SupportingText sits at 16dp, section labels at 32dp, card text at 72dp · fix: drop StepHeading's horizontal xs so heading, footer and labels share 16dp (labels via LocusSection) · feature/onboarding/src/main/kotlin/dev/locus/feature/onboarding/OnboardingScreen.kt:252 · before/feature-onboarding/onboarding-intro_360-dark.png · fixed in #187 → after/feature-onboarding/onboarding-intro_360-dark.png

#### Privacy
- [x] S1 · "Check GitHub for new versions?" subtitle is cut at 4 lines, hiding "this bypasses app-store checks" and the Settings path the user is consenting to · fix: see SwitchRow; or shorten to one line + SupportingText below · feature/onboarding/src/main/kotlin/dev/locus/feature/onboarding/OnboardingScreen.kt:185 · before/feature-onboarding/onboarding-privacy_360-dark.png · fixed in #187 → after/feature-onboarding/onboarding-privacy_360-dark.png
- [x] S2 · Fact rows cap at 4 lines: "With the on-device model, nothing leaves …" loses its conclusion; at 200% every fact is cut mid-sentence · fix: no cap on Fact subtitles (they are the content of the step) · feature/onboarding/src/main/kotlin/dev/locus/feature/onboarding/OnboardingScreen.kt:260 · before/feature-onboarding/onboarding-privacy_360-light.png · fixed in #187 → after/feature-onboarding/onboarding-privacy_360-light.png

#### Model
- [x] S2 · Provider descriptions are cut at 2 lines mid-instruction: "Enter the model ID from a…", "over the Responses API (keeps reasoning …" · fix: subtitleMaxLines 3+ or shorter copy · feature/onboarding/src/main/kotlin/dev/locus/feature/onboarding/ProviderStep.kt:83 · before/feature-onboarding/onboarding-provider_360-dark.png · fixed in #187 → after/feature-onboarding/onboarding-provider_360-dark.png

#### Your agent (identity)
- [x] S2 · "What do you want help with?" chips sit loose at the screen edge (16dp) under a label at 32dp, while the other answers sit in cards; mixed structure on one step · fix: put the chips in a LocusCard like "How should it talk?" · feature/onboarding/src/main/kotlin/dev/locus/feature/onboarding/IdentityStep.kt:104 · before/feature-onboarding/onboarding-identity_360-dark.png · fixed in #187 → after/feature-onboarding/onboarding-identity_360-dark.png
- [x] S2 · "Check in / Balanced / Independent" truncates at 200% (Segmented) · fix: see Segmented · core/designsystem/src/main/kotlin/dev/locus/core/designsystem/Components.kt:431 · before/feature-onboarding/onboarding-identity_360-light-font200.png · fixed in #186 → after/feature-onboarding/onboarding-identity_360-light-font200.png

#### Workspace and Linux / Ready
- [x] S2 · "you can do it later from Home → Files → Linux" and "Install it any time from Home → Files → Linux" are plain text paths, and the screen they name is titled "Workspace" · fix: DestinationLink to Workspace, and call it Workspace · feature/onboarding/src/main/kotlin/dev/locus/feature/onboarding/SetupSteps.kt:146 · before/feature-onboarding/onboarding-linux_360-dark.png · fixed in #192 → after-open/feature-onboarding/onboarding-linux_360-dark.png
- [x] S3 · Same plain path on the Ready summary row · fix: Summary with a link to Workspace · feature/onboarding/src/main/kotlin/dev/locus/feature/onboarding/OnboardingScreen.kt:240 · before/feature-onboarding/onboarding-done_360-dark.png · fixed in #192 → after-open/feature-onboarding/onboarding-done_360-dark.png
- [x] S3 · Button says "Download and install Linux and 2 tools" here and "Install Linux and 2 tools" in Workspace · fix: one wording · feature/onboarding/src/main/kotlin/dev/locus/feature/onboarding/SetupSteps.kt:146 · before/feature-onboarding/onboarding-linux_360-dark.png · fixed in #187 → after/feature-onboarding/onboarding-linux_360-dark.png

#### Machines (SSH)
- [x] S3 · At 200% the Port field label wraps to "Por / t" and overlaps the outline · fix: give Port a min width (~96dp) or stack it under Host at large font · feature/onboarding/src/main/kotlin/dev/locus/feature/onboarding/SetupSteps.kt:216 · before/feature-onboarding/onboarding-ssh-hosts_360-light-font200.png · fixed: Port goes under Host at a large font in #192 → after-open/feature-onboarding/onboarding-ssh-hosts_360-light-font200.png

#### Connectors
- [x] S2 · "Add key" is drawn as a neutral status chip, identical in shape to "Connected"; an action looks like a state, and descriptions cut at one line ("Web search and page rea…") · fix: trailing TextButton "Add key" (or chevron) and subtitleMaxLines 2 · feature/onboarding/src/main/kotlin/dev/locus/feature/onboarding/SetupSteps.kt:270 · before/feature-onboarding/onboarding-connectors_360-dark.png · fixed in #187 → after/feature-onboarding/onboarding-connectors_360-dark.png

### feature-workspace

#### Workspace (Linux card)
- [x] S2 · Card header: the "Not installed"/"Ready" chip squeezes the subtitle, so at 200% it wraps one syllable per line ("works / pace / at / / works / pace") · fix: chip under the title at narrow widths, or subtitle full width below the header row · feature/workspace/src/main/kotlin/dev/locus/feature/workspace/linux/LinuxCard.kt:100 · before/feature-workspace/workspace_360-light-font200.png · fixed in #187 → after/feature-workspace/workspace_360-light-font200.png
- [x] S3 · Installed state stacks five buttons of three kinds (filled, two outlined, two text, one red) in a FlowRow; no single focus · fix: Open terminal primary, Health & details as a row, Repair/Reinstall/Remove in an overflow or a "Manage" sheet · feature/workspace/src/main/kotlin/dev/locus/feature/workspace/linux/LinuxCard.kt:203 · before/feature-workspace/workspace-linux-installed_360-dark.png · fixed: Open terminal and Health & details; Repair, Reinstall and Remove in a Manage sheet in #192 → after-open/feature-workspace/workspace-linux-installed_360-dark.png

#### Trash
- [x] S2 · File names and reasons are cut to "budget-2026.…", "Deleted · Just n…" at 360dp because Restore + ✕ take the row · fix: Restore as a CompactIconButton or move actions into the row's tap sheet; subtitle 2 lines · feature/workspace/src/main/kotlin/dev/locus/feature/workspace/WorkspaceScreen.kt:275 · before/feature-workspace/workspace-trash_360-dark.png · fixed in #187 → after/feature-workspace/workspace-trash_360-dark.png

#### File info preview
- [x] S2 · Three buttons in a non-wrapping Row: "Export" breaks to "Exp / ort" at 360dp 100% and "Share" to "Shar / e" at 200% · fix: FlowRow or stacked full-width buttons · feature/workspace/src/main/kotlin/dev/locus/feature/workspace/preview/MediaViewers.kt:122 · before/feature-workspace/workspace-preview-file-info_360-dark.png · fixed in #187 → after/feature-workspace/workspace-preview-file-info_360-dark.png

#### Code / JSON preview
- [x] S3 · Line-number gutter #6D6E6C on #EAE9E5 is ~4.2:1 (render guard, every line number); borderline but flagged on every line · fix: onSurfaceVariant for the gutter text · feature/workspace/src/main/kotlin/dev/locus/feature/workspace/preview/CodeViewer.kt:133 · before/feature-workspace/workspace-preview-code_360-light.png · fixed in #187 → after/feature-workspace/workspace-preview-code_360-light.png

#### Linux health
- [x] S2 · Error state shows the raw probe message "proot exited with code 255 before the probe finished" with no guidance · fix: plain text ("Linux didn't start") plus Repair as the action, raw message in a details row · feature/workspace/src/main/kotlin/dev/locus/feature/workspace/linux/health/LinuxHealthScreen.kt:177 · before/feature-workspace/linux-health-error_360-dark.png · fixed in #187 → after/feature-workspace/linux-health-error_360-dark.png · Repair button added in #192 → after-open/feature-workspace/linux-health-error_360-dark.png
- [x] S2 · "Problem" chip text #FF8A80 on its container over the critical-glow card is below AA in dark (render guard) · fix: drop the card glow behind the chip or use onCriticalContainer on an opaque container · feature/workspace/src/main/kotlin/dev/locus/feature/workspace/linux/health/LinuxHealthScreen.kt:301 · before/feature-workspace/linux-health-problems_360-dark.png · fixed in #187 → after/feature-workspace/linux-health-problems_360-dark.png
- [x] S2 · Storage/memory detail rows cut paths and labels ("/usr/lib/nod…", "Phone mem…", "Linux progra…") (dense KeyValueRow) · fix: see KeyValueRow · feature/workspace/src/main/kotlin/dev/locus/feature/workspace/linux/health/LinuxHealthScreen.kt:408 · before/feature-workspace/linux-health_360-dark.png · fixed in #187 → after/feature-workspace/linux-health_360-dark.png
- [x] S2 · At 200% check titles and fixes cut ("Linux doesn't re…", "Its log may say why; ask the agent to c…") because the trailing action takes the row · fix: see ListRow; action under the text when narrow · feature/workspace/src/main/kotlin/dev/locus/feature/workspace/linux/health/LinuxHealthScreen.kt:372 · before/feature-workspace/linux-health-broken_360-light-font200.png · fixed in #187 → after/feature-workspace/linux-health-broken_360-light-font200.png
- [x] S3 · Headline "Linux doesn't respond (+1 more)" · fix: "Linux doesn't respond, and 1 more problem" or just the first title with the count in the line below · core/linux/src/main/kotlin/dev/locus/core/linux/LinuxHealth.kt:261 · before/feature-workspace/linux-health-broken_360-dark.png · fixed in #187 → after/feature-workspace/linux-health-broken_360-dark.png

#### Android's background limit sheet
- [x] S2 · Body column adds horizontal Spacing.xl inside LocusSheet, so text starts at 48dp while the title is at 24dp · fix: drop the extra padding · feature/workspace/src/main/kotlin/dev/locus/feature/workspace/linux/health/LinuxHealthScreen.kt:521 · before/feature-workspace/linux-sheet-phantom-help_360-dark.png · fixed in #187 → after/feature-workspace/linux-sheet-phantom-help_360-dark.png
- [x] S3 · The adb command is inline prose in backticks and wraps mid-identifier at 200% · fix: put the command on a CodeSurface with a copy button · core/linux/src/main/kotlin/dev/locus/core/linux/AndroidKill.kt:28 · before/feature-workspace/linux-sheet-phantom-help_360-light-font200.png · fixed in #192 → after-open/feature-workspace/linux-sheet-phantom-help_360-light-font200.png

#### Packages
- [x] S2 · "Updates are checked when you ask…" note beside "Check for updates" becomes a one-word column at 200% ("Update / s are / checke / d when") · fix: note on its own line, button under it · feature/workspace/src/main/kotlin/dev/locus/feature/workspace/linux/health/LinuxPackagesScreen.kt:206 · before/feature-workspace/linux-packages_360-light-font200.png · fixed in #187 → after/feature-workspace/linux-packages_360-light-font200.png
- [x] S3 · Search is a raw OutlinedTextField, not the shared SearchField; package rows sit loose with full-bleed dividers while the updates block is a card · fix: SearchField and a CardGroup for the list · feature/workspace/src/main/kotlin/dev/locus/feature/workspace/linux/health/LinuxPackagesScreen.kt:164 · before/feature-workspace/linux-packages_360-dark.png · fixed in #187 → after/feature-workspace/linux-packages_360-dark.png

#### Terminal (no Linux), Markdown/table/image previews, empty states
- No issues found.

### feature-tasks

#### Tasks
- [x] S2 · "1 running" StatusLine is #1E8E50 on #F7F6F2, below AA (render guard) · fix: see StatusLine · core/designsystem/src/main/kotlin/dev/locus/core/designsystem/Components.kt:342 · before/feature-tasks/tasks_360-light.png · fixed in #186 → after/feature-tasks/tasks_360-light.png
- [x] S3 · The Battery SettingsLink under the card is padded start = xs, so it starts 4dp right of the card edge · fix: no extra start padding · feature/tasks/src/main/kotlin/dev/locus/feature/tasks/TasksScreen.kt:167 · before/feature-tasks/tasks_360-dark.png · fixed in #187 → after/feature-tasks/tasks_360-dark.png

#### Tasks empty / error / loading / all paused
- No issues found.

### feature-activity

#### Activity
- [x] S2 · "10 actions today · 1 failed" is caution #B0680A on #F7F6F2, 4.02:1 (render guard; also filter-failed, no-matches, run-expanded) · fix: see StatusLine · feature/activity/src/main/kotlin/dev/locus/feature/activity/ActivityScreen.kt:192 · before/feature-activity/activity_360-light.png · fixed in #187 → after/feature-activity/activity_360-light.png

#### Activity chat / empty / no matches
- No issues found.

### feature-analytics

#### Insights
- [x] S2 · At 200% the legend Row has no wrapping: "63% cached" breaks one or two letters per line ("63 / % / ca / ch / ed") · fix: FlowRow for the legend · feature/analytics/src/main/kotlin/dev/locus/feature/analytics/AnalyticsScreen.kt:202 · before/feature-analytics/analytics-prices-off_360-light-font200.png · fixed in #187 → after/feature-analytics/analytics-prices-off_360-light-font200.png
- [x] S2 · Figure labels truncate at 200% ("Reque…", "Media…", "Slowes…") and "First token after 1…" · fix: let labels wrap to 2 lines, or two figures per row at large font · feature/analytics/src/main/kotlin/dev/locus/feature/analytics/AnalyticsScreen.kt:527 · before/feature-analytics/analytics-prices-off_360-light-font200.png · fixed in #187 → after/feature-analytics/analytics-prices-off_360-light-font200.png

#### Insights, prices off
- [x] S2 · Spend figures read "– / Unknown / –": three spans with prices off show two formats, and "Unknown" for 7 days only · fix: one value for all three ("–") with the note explaining why · feature/analytics/src/main/kotlin/dev/locus/feature/analytics/AnalyticsScreen.kt:271 · before/feature-analytics/analytics-prices-off_360-dark.png · fixed in #187 → after/feature-analytics/analytics-prices-off_360-dark.png
- [x] S3 · "Settings → Models" link renders faded (disabled because the test has no settings host); on device it is enabled, so likely a harness artefact · fix: provide ProvideSettingsLinks in the screenshot test · feature/analytics/src/main/kotlin/dev/locus/feature/analytics/AnalyticsScreen.kt:290 · before/feature-analytics/analytics-prices-off_360-light.png · fixed in #187 → Models" link renders faded (disabled because the → after/feature-analytics/analytics-prices-off_360-light.png

#### Insights empty / loading / prices failed / fetching / all time
- No issues found.

### feature-browser

#### Browser (page, tabs, loading, dialogs)
- [x] S3 · Every page render shows "Nothing open yet" under a real page title and URL (no WebView/activity in Robolectric), so the page state has no usable golden and the screen contradicts itself in review · fix: render a placeholder page surface in tests, or show "Page loads here" when activity is null · feature/browser/src/main/kotlin/dev/locus/feature/browser/BrowserScreen.kt:117 · before/feature-browser/browser-page_360-dark.png · fixed in #187 → after/feature-browser/browser-page_360-dark.png
- [x] S3 · Tab-count glyph's "3" is clipped/overlaps its frame at 200% (fixed 20dp box with scaled text) · fix: size the box from the text or cap the font scale on the digit · feature/browser/src/main/kotlin/dev/locus/feature/browser/BrowserScreen.kt:209 · before/feature-browser/browser-tabs_360-light-font200.png · fixed in #187 → after/feature-browser/browser-tabs_360-light-font200.png
- [x] S3 · Browser still uses Scaffold + navigationBarsPadding rather than LocusScreen · fix: LocusScreen · feature/browser/src/main/kotlin/dev/locus/feature/browser/BrowserScreen.kt:81 · before/feature-browser/browser-page_360-dark.png · fixed in #187 → after/feature-browser/browser-page_360-dark.png

#### Hand-off banner
- [x] S2 · At 200% the "Your turn" banner text is cut at 4 lines ("it shows the p…") beside Done · fix: actionsBelow = true · feature/browser/src/main/kotlin/dev/locus/feature/browser/BrowserScreen.kt:110 · before/feature-browser/browser-handoff_360-light-font200.png · fixed in #187 → after/feature-browser/browser-handoff_360-light-font200.png

### core-ui

#### Workflow editor
- [x] S2 · Tool list rows show raw tool ids as titles (list_files, read_file, run_command) · fix: toolLabel() name with the id in a mono caption if needed · core/ui/src/main/kotlin/dev/locus/core/ui/WorkflowSheets.kt:136 · before/core-ui/workflow-editor_360-dark.png · fixed in #187 → after/core-ui/workflow-editor_360-dark.png
- [x] S3 · Slot fields have different widths per row because the type TextButton ("path", "number", "boolean") is content-sized; the type reads as a link, not a picker · fix: fixed-width dropdown field for type · core/ui/src/main/kotlin/dev/locus/core/ui/WorkflowSheets.kt:192 · before/core-ui/workflow-editor_360-dark.png · fixed in #192 → after-open/core-ui/workflow-editor_360-dark.png

#### Workflow picker empty
- [x] S3 · Empty picker is a SectionNote at 40dp under a 24dp title, no EmptyState · fix: SupportingText at the sheet inset (or EmptyState) · core/ui/src/main/kotlin/dev/locus/core/ui/WorkflowSheets.kt:291 · before/core-ui/workflow-picker-empty_360-dark.png · fixed in #187 → after/core-ui/workflow-picker-empty_360-dark.png

#### Telegram sheet
- [x] S2 · "This is a group or channel: everyone in it will see your notifications." is caution text on the sheet below AA (render guard) · fix: caution Banner or Tone.Caution.onContainer() · core/ui/src/main/kotlin/dev/locus/core/ui/TelegramSheet.kt:181 · before/core-ui/telegram-sheet-group_360-light.png · fixed in #187 → after/core-ui/telegram-sheet-group_360-light.png
- [x] S3 · Bot token field uses its label as a long placeholder ("Bot token (blank keeps the saved one)") that wraps to two lines; at 200% "Open @BotFather" breaks to "@BotF / ather" · fix: label "Bot token", supporting text "Leave blank to keep the saved one"; actions stacked at large font · core/ui/src/main/kotlin/dev/locus/core/ui/TelegramSheet.kt:133 · before/core-ui/telegram-sheet-connected_360-dark.png · fixed in #187 → after/core-ui/telegram-sheet-connected_360-dark.png

#### SSH auth fields
- [x] S2 · Auth choice truncates to "Privat… Passw…" at 200% (Segmented) · fix: see Segmented · core/designsystem/src/main/kotlin/dev/locus/core/designsystem/Components.kt:431 · before/core-ui/ssh-auth-fields_360-light-font200.png · fixed in #186 → after/core-ui/ssh-auth-fields_360-light-font200.png

#### Workflow run / run error
- [x] S2 · At 200% the title is "Dep lo…" beside "Run in a new chat" (LocusSheet header) · fix: see LocusSheet · core/designsystem/src/main/kotlin/dev/locus/core/designsystem/Components.kt:553 · before/core-ui/workflow-run-error_360-light-font200.png · fixed in #186 → after/core-ui/workflow-run-error_360-light-font200.png

### core-markdown

#### Artifact card, too large
- [x] S2 · "View the source instead." points at an unlabelled </> icon; the only way forward has no text and no button shape · fix: a "View source" TextButton in the note · core/markdown/src/main/kotlin/dev/locus/core/markdown/artifact/ArtifactCard.kt:191 · before/core-markdown/artifact-card-too-large_360-dark.png · fixed in #187 → after/core-markdown/artifact-card-too-large_360-dark.png

#### Math blocks
- [x] S3 · Minus signs render as hyphens ("n - 1", "x_i - x̄"), visibly shorter than "+" and "=" · fix: map "-" to U+2212 in math mode · core/markdown/src/main/kotlin/dev/locus/core/markdown/MathText.kt · before/core-markdown/math-blocks_360-light.png · fixed in #192 → after-open/core-markdown/math-blocks_360-light.png
