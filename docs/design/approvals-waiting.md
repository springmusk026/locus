# Questions waiting

Status: built 2026-11-21 (the "Smaller" list of [feature opportunities](../research/feature-opportunities-2026-11.md), after OpenMinis 1.16's "questions waiting" banner). Not yet tried on the phone. Code: `WaitingChats`, `waitingKinds`, `trackWaiting` (core/data `agent/WaitingChats.kt`), `WaitingChatsViewModel`, `waitingEntries`, `waitingBanner`, `WaitingChatsBanner` (feature/chat `WaitingBanner.kt`), placed by `ChatScreen` (under the title) and `ConversationsScreen` (home).

Several chats can wait on the user at once: an approval in one, an `ask_user` question in another, a third paused at its budget. Inside a chat you only saw your own; the others waited, unseen, until the notification shade or home said so.

## What the user sees

A compact caution card, "2 chats are waiting for you", with one line under it: "Oldest: “Plan a trip” · Has a question for you". A tap opens the chat that has waited longest. Nothing is answered from the banner; the chat's own card does that.

- **In a chat**: under the title bar, counting every chat but this one. It slides in and out as chats start and stop waiting.
- **On home**: under the featured card. The featured card already shows one chat waiting for an approval, so the banner leaves that chat out and says "1 more chat is waiting for you"; with nothing featured it says "1 chat is waiting for you".
- **One chat**: "“Trip plan” · Needs your approval". **Several**: "Oldest: …". A chat with no title yet is "An untitled chat".
- **Why**, in order: an approval (its own or a helper's: "Needs your approval"), else a question ("Has a question for you"), else a budget pause ("Paused at its budget").

## Rules

- **Who counts.** A chat waits when its `RunState` has an approval, a question or a budget pause (`waitingKinds`). Scheduled runs never wait (they're read-only and never ask). Helpers' asks land in their parent chat, so the parent is the one counted.
- **Oldest.** Locus notes when it first saw each chat waiting (`trackWaiting`); a chat keeps that time while it waits, even if what it waits for changes, and loses it once nothing waits. `WaitingChats` is an app-wide singleton fed by `AgentRunner.states` (the flow the notification and widget use), started eagerly, so "oldest" is right even if no screen showed the banner meanwhile. Ties go by chat id, so the order never flickers. In memory only: after a restart nothing is pending anyway (an unanswered approval is a denial).
- **App lock.** A locked chat is counted but never named ("A locked chat", "Oldest: a locked chat"), even after this session unlocked it: the banner is read at a glance, over another chat. Until the lock's settings have been read, no chat is named. Opening a locked chat from the banner goes through `ChatLockGate`, like any other way in ([app lock](app-lock.md)).
- **No polling.** It recomputes only when who is waiting changes; streamed text and tool progress don't touch it.

## Not a "Needs you" feed

The roadmap's "Needs you" feed (trigger results, drafts, approvals in one place) is a different, bigger thing. The banner is deliberately one small component with its own data (`WaitingChats`), so when the feed lands it can take over home's slot, or link to it, without either depending on the other.

## Tests

`WaitingChatsTest` (core/data): what each chat waits for and in which order, scheduled runs left out, keeping the first-seen time, the flow over changing run states. `WaitingBannerTest` (feature/chat): titles, locked chats counted but never named (unlocked session too), unnamed until the lock is read, leaving out the chat on screen, opening the oldest, "more" on home. Screenshots: `chat-waiting-banner`, `chat-waiting-banner-locked`, `home-waiting`, `home-waiting-locked`.
