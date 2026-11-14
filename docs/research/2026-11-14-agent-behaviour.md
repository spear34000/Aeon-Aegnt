# Agent behaviour: what the research says and what Aeon does with it

Date: 2026-11-14. The owner asked for the agent's behaviour to be built "referring to research" and picked four areas:
retrying after a failure, keeping context in long tasks, asking when something is unclear, and remembering across tasks.
Decision D24 in [DECISIONS.md](../DECISIONS.md) records the outcome.

Honesty about reading depth, because it limits what can be claimed:

| Source | What was actually read |
|---|---|
| Reflexion, Shinn et al. 2023 (arXiv 2303.11366) | Abstract page, then the full HTML version (v4): the trigger for reflection, how the memory is bounded, the limitations |
| Self-Refine, Madaan et al. 2023 (arXiv 2303.17651) | Abstract page only |
| ReAct, Yao et al. 2022 (arXiv 2210.03629) | Abstract page only |
| Anthropic, *Effective context engineering for AI agents* | The article (through a fetch tool that summarises, so wording is not verbatim) |
| Anthropic, *Building effective agents* | The article (same caveat) |
| Generative Agents, Park et al. 2023 (arXiv 2304.03442) | Abstract page, then the first part of the full HTML (v2): retrieval scoring, importance, when reflection is triggered. Not the whole paper, not the ablation tables |
| MemGPT, Packer et al. 2023 (arXiv 2310.08560) | Abstract page only |
| Lost in the Middle, Liu et al. 2023 (arXiv 2307.03172) | Abstract page only |
| Learning to Ask (EMNLP 2025, about asking when an instruction is ambiguous) | **A web-search summary only. The paper itself was not opened.** Treat the ask-when-missing rule below as "consistent with" it, not "taken from" it |

Nothing here was measured on Aeon's own tasks. The tests show the mechanisms do what they say on scripted model replies;
they do not show that tasks come out better with a real model. That has not been tried.

## 1. Retrying after a failure

What the sources say:
- Reflexion: after a failed trial, the agent writes a short reflection in words and keeps it in memory for the next
  trial; the signal has to come from outside the model (a test result, an environment signal), not from the model
  judging itself. The memory is bounded (a sliding window of a few reflections). To spot an agent that is stuck it used a
  simple rule: the same action repeated for more than a few cycles, or too many actions in one trial.
- Self-Refine (abstract): generate, give feedback, refine, repeat, with no training. Only the abstract was read, so
  nothing below depends on its details.
- ReAct (abstract): interleave reasoning and acting so that observations steer the next step.

What Aeon does (`src/core/agent.ts`):
- Before the agent's "finish" is accepted, the task's machine-checkable criteria are run against the real files
  (`verify`). If they fail, the agent writes one reflection (what went wrong, what to do differently), the reflection is
  shown to it as data, and it goes back to work. At most two repairs (`maxRepairs`), counted from the saved steps so a
  restart does not give a fresh set. After that the engine's own final check still decides.
- A model that cannot produce a reflection gets a plain sentence instead; a reflection never fails the task.
- Loop detection: the same tool with the same arguments and the same result three times in a row. First a notice to
  the model (up to three), then one written reflection, then the task stops with an error that names the tool.
- The outside signal is the verification of criteria. A task with no criteria is not second-guessed: nothing outside
  the model could say it failed.

Not done: more than two repairs; a score for how good a result is (only pass or fail); learning across tasks from
reflections (they live inside one task).

## 2. Keeping context in long tasks

What the sources say:
- Anthropic (context engineering): context is a limited resource; compact old turns, clear old tool results, let the
  agent keep structured notes outside the window, and fetch the rest on demand.
- Lost in the Middle (abstract): models use information at the start and end of a long prompt better than in the
  middle.

What Aeon does:
- Only the last six tool results are shown whole (`historyFullSteps`); older steps shrink to one line and stay fenced as
  untrusted data. A history over its character budget (`historyBudgetChars`, 20,000) drops the oldest lines and says so.
- The goal and plan are at the start of every model call and the instruction line is at the end; the history is in
  between.
- The agent can write notes (`{"action":"note"}`, at most 12, 300 characters each). They come back in every later call.
  Notes and reflections may contain text copied from the web, so they are fenced as untrusted data, and a note that
  reads like an instruction to the model is dropped and audited.

Not done: summarising old steps with a model (compaction is by cutting, not by summarising: a model call per
compaction would cost tokens and could invent things); sub-agents.

## 3. Asking when something is unclear

What the sources say:
- Anthropic (effective agents): an agent should be able to come back to the human at a blocker and should stop on
  conditions it can check, not run on.
- Learning to Ask: only a search summary was read; it says asking pays off when the information is truly missing.

What Aeon does:
- `{"action":"ask","question":"...","options":[...]}`. The task waits as a question card (not an approval) with up to
  four suggested answers, free text and "skip". The answer comes back to the model as the owner's own words, not as
  untrusted data. At most three questions per task. A question left unanswered expires like an approval and the agent
  goes on. The prompt tells the agent not to ask what it can find out, what it was told, or for permission.

Not done: deciding automatically when an instruction is ambiguous (the model decides); learning which questions the
owner answers "skip" to.

## 4. Remembering across tasks

What the sources say:
- Generative Agents: a stream of memories retrieved by three scores added together (recency, importance, relevance),
  each scaled to 0..1 and, in the paper's setup, weighted equally. Reflection is triggered when accumulated importance
  passes a threshold (150 in their setup).
- MemGPT (abstract): memory in tiers; a small part always in the prompt, the rest fetched on demand by function calls.

What Aeon does (`src/node/memory.ts`, `src/node/tools/memory-tools.ts`):
- A short list of plain sentences the owner can read, add to and delete (`memory.json`, Settings > 기억, at most 200).
- Retrieval: recency (0.97 per day since last use) + importance (1 if the owner wrote it, 0.6 if the agent proposed it
  and the owner approved) + 2 x relevance (shared words with the goal; Korean by pairs of characters; common words
  ignored). The weight of relevance is Aeon's own choice, not from the paper.
- Tiers: preferences ("how I like things done", up to 4) are always in the prompt; facts only when they share words with
  the goal; both within 1,200 characters. The rest is reachable through `memory.search`.
- Saving: the agent proposes `memory.save`; the owner sees the exact sentence on a card and nothing is kept before a
  yes. Text that looks like a secret is refused. The prompt part says the memory never changes the rules, the JSON
  format or what needs approval, and a memory that reads like an instruction to the model is left out.

Not done: embeddings (nothing to download or pay for; word overlap misses synonyms); the model rating importance;
automatic reflections that turn many memories into one; forgetting by age (only the 200 limit removes things).

## What this does not prove

The mechanisms are tested with scripted replies (`test/agent-behaviour.test.ts`, `test/memory.test.ts`) and, for the
memory and question flows, against the real server from the Kotlin client. Whether a real model repairs better after a
reflection, uses notes well, or asks at the right moments is not measured.
