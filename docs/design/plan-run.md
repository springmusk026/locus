# Plan on one model, run on another

Plan mode's **Run with**: make the plan on the chat's (strong, expensive) model, approve it, and have a model you
pick (cheaper or faster) carry it out. Written 2026-11-22 for the roadmap line "Plan mode: plan on one model,
execute on another". No database change.

## What Locus had

- **Plan mode** (`Conversation.planMode`, the composer's + sheet): a user message sent with it on carries
  `ModeNote.PLAN_MODE`; `ChatPolicy.denial` refuses every call that isn't an observation; the model calls
  `plan_propose`, which ends the turn (`TURN_ENDING_TOOLS`). The chat shows the plan as a card with **Approve and
  run**, **Edit** and **Cancel** (`PlanCard`). Approve turns plan mode off and sends
  `AgentControlTools.PLAN_APPROVED` ("Approved. Go ahead with the plan.") as an ordinary user message
  (`AgentRunner.approvePlan`), which starts a new task on the chat's model.
- **Changing a chat's model** (`ConversationRepository.setModel`, the model sheet) changes the provider and model
  for later turns; earlier turns stay as they are and are replayed to the new model (their `rawPayload` only to the
  same wire format, else rebuilt from parts). The sheet says: "Switching model starts a fresh prompt cache for this
  chat, so the next reply reads the whole conversation again." The system prompt and tool list never change.
- **Compare models** ([compare-models](compare-models.md)) already sends one turn of a chat to a second model:
  `run(compare = …)` builds that model's provider config from the chat's (`chatProvider`, the chat's effort mapped
  to its format), picks only models that can serve the chat (`compareNeeds`, `canCompareWith`), prices its first
  request as a cache write, and asks before sending.

## Alternatives

- **Anthropic's advisor tool** (beta `advisor_20260301`, [algorithms, also worth doing](../research/agent-algorithms-2026.md#also-worth-doing)):
  a cheap model works and consults a stronger one inside one request. It's a tool in the request, so it has to be
  chosen when a chat is created (adding it to an existing chat changes the cached prefix), it's Anthropic-only and
  in beta, and the strong model advises rather than plans. Not built here; it remains a separate item.
- **Switch the chat's model on approval** (as if the user picked it in the model sheet). Simple, but the chat then
  stays on the cheaper model for everything after, silently, and its project or default model is lost. Rejected.
- **Run the plan on another model for the approval's task only** (chosen). The chat's own model never changes; the
  pick applies to exactly one task, and the chat says when it's back.

## Behaviour

1. **Pick.** While a plan waits, its card has a **Run with** row: the chat's own model by default ("This chat's
   model. Tap to run the plan on another one."). Tapping it opens the model sheet in plan-run mode
   (`ModelSheet(planRun = true)`, `ControlSheet.PlanRun`): the same search, favourites, recents and capability
   chips; the chat's own model is marked "This chat's model" and stays pickable (picking it goes back to the
   default); the pick is marked "Runs the plan". Models known not to serve the chat can't be picked, exactly as in
   Compare (`compareNeeds`: tool calling whenever the chat declares tools, vision when the context has images, a
   window that holds the context so far; unknown never disqualifies, [model capabilities](model-capabilities.md)).
   With a pick the row reads "claude-haiku-5-5 (Anthropic) · For this plan only. Then the chat goes back to
   claude-opus-5-5." The pick lives in the chat screen's view model (`ChatViewModel.planRunWith`), in memory only,
   like Compare's: after a restart the plan runs on the chat's model unless picked again. Cancel drops it. It
   carries over to a revised plan (Edit, then a new plan) until an Approve uses it up.
2. **Ask.** Approve with another model may ask first (`PlanRun.needsConfirm`, core/model):
   - **always** when the model is at another provider than the chat's: the whole conversation is about to go to a
     provider the user didn't pick for this chat. The sheet starts with "This sends the whole conversation so far
     to OpenAI, a provider this chat hasn't used.";
   - the first time (`AppSettings.planRunConfirmed`), to say what it means;
   - afterwards only when the run model's first request is estimated over $0.05 (`ModelCompare.CONFIRM_ABOVE_USD`).
     An unknown price doesn't ask again after the first confirmation, as in Compare.

   The sheet shows the **first request's input** on both models (`PlanRun.estimate`): the run model priced as a
   cache write of the whole prompt (it has never seen this chat; at the 1-hour price on Anthropic, where the chat's
   own runs ask for an hour, [cost](cost.md#cache-lifetimes)), and the chat's model priced warm (context read from
   its cache) for comparison, labelled "if its cache is still warm". Then: "Later steps read most of it from the
   cache; replies and tool steps cost more." and "When the plan's task ends, the chat goes back to claude-opus-5-5
   for your next message." Cancel runs nothing: the plan still waits and the pick stays.
3. **Run.** `AgentRunner.approvePlan(chat, runOn)` checks the model first (`planRunProblem`): its provider must
   still exist (`PlanRun.available`: unlike a chat's own provider, a removed one never falls back to the default),
   have its key (`ProviderAccess.missing`), and not be known to lack tool calling when the chat uses tools. If any
   fails, nothing is stored or sent, plan mode stays on, and a notice says why ("Couldn't run the plan on gpt-5-mini
   (OpenAI): add an API key… Pick another model, or run it on this chat's."). Otherwise one task starts: plan mode
   off, the usual approval message, then `run()` on that model. Every turn of that task runs there (the approval
   turn, messages you queue meanwhile, helper reports and run-anyway calls picked up before the task ends), with
   the chat's permissions, mode, project limits, steering, completion check and safety reviewer, and the backup
   chain resolved for that model (a backup is a separate user choice and shows as usual). A compaction the chat
   needs before the turn still runs on the chat's own model.
4. **Watch.** While it runs, "Running the plan: claude-haiku-5-5 (Anthropic)" sits above the streaming text
   (`RunState.planRun`). Each reply is stored with the model that wrote it (`ReplyInfo`), as always.
5. **Back.** When that task ends, however it ends (done, stopped, failed, a budget stop), the chat is back on its
   own model: the runner forgets the pick (`planRuns`, removed in `startRun`'s cleanup) and says so, unless the run
   ended with a notice of its own: "The plan ran on claude-haiku-5-5 (Anthropic). This chat is back on
   claude-opus-5-5 (Anthropic) for your next message." The next message, Continue and Try again go to the chat's
   model; the composer's model chip never changed. Afterwards the plan card reads "Approved · ran on
   claude-haiku-5-5" (`planRanOn`, from the first reply after the approval), so it's visible later and after a
   restart.

**Why back to the chat's model:** the user chose the chat's model for the chat and the other one for a plan. Staying
on the cheaper model would change every later answer without a visible choice; going back is the default that can't
surprise anyone, and the notice and the card say it. To keep working on the cheaper model, pick it in the model
sheet as for any switch. Left out: Try again or Continue on the plan's model after its task failed or was stopped
(the plan is already approved; pick the model in the model sheet, or approve a new plan).

## Prompt cache and requests

- **Nothing that existing chats send changes.** A chat without a pick, and every request of the chat's own model,
  is byte for byte what it was. Approve without a pick still goes through `send`.
- **What the run model receives:** the chat's own frozen system prompt (`conversation.systemPrompt`) and its fixed
  tool list (`tools.forChat(conversation)`), translated by that model's adapter like any backup or Compare model's,
  and the same append-only path: the user's request with its plan-mode note, the `plan_propose` call and its result,
  then the approval message. Nothing is rewritten or summarised for it; the plan reaches it as the tool call it was.
  Older assistant turns replay their `rawPayload` only to the same wire format, exactly as after switching the
  chat's model. Effort is the chat's, mapped to the run model's format (`chatProvider`). OpenAI's
  `prompt_cache_key` stays the chat's (`locus-chat-<id>`); caches are per model, so it only routes.
  `PlanRunTest` (core/data) checks the system message, the tools and the prefix against the planning request.
- **Its first request writes the whole prefix to its provider's cache** (it has never seen this chat); later steps
  read it, as in any chat. That write is what the estimate prices.
- **The chat's model keeps its cache.** Its entries are keyed by its prefix, which is unchanged. When the chat goes
  back to it, its next request reads the prefix up to the plan from its cache if that hasn't expired (Anthropic:
  an hour for the conversation on the user's own runs) and writes the run's turns once. The model sheet's plan-run
  note says it: "This chat's own model keeps its cache; when the plan is done, its next reply reads the plan's work
  fresh."

## Budgets and receipts

- **Task budget.** The approval's task gets a budget whose cost limit applies when the **run model's** prices are
  known (`newBudget(runsOn = …)`); each request is priced at its own model, so a backup or a compaction on the chat's
  model counts at its price. Planning was its own task with its own budget; a budget can't pause a task after it
  ended, so the two aren't merged.
- **Receipt.** The task that starts with the approval gets the task before it, the one that proposed the plan, as
  `TaskReceipt.plan` (`TaskReceipts.build`, core/agent): its models and cost. The receipt sheet shows **Plan**
  ("≈$0.21 · claude-opus-5-5") and **Plan and run** (both together; a floor when one side's price is unknown),
  beside the run's own Models and Cost rows. This applies to every approved plan, on any model. Insights already
  counts every request at its own model.

## Not done

- A per-project or default "run plans with" model; the pick is per plan and in memory only.
- Try again or Continue on the plan's model after its task stopped or failed.
- The advisor tool (see Alternatives).
- The plan's cost in a receipt's export text and notification line (the sheet shows it).
