# Search rules

The exact rules the solver uses to search fewer branches, one entry per rule, so they can be
refined one at a time. Each rule has an id (R1, S1, ...) to cite in issues and commits.

There are two kinds:

- **Reductions (R).** They change which options a decision offers. A reduction can be unsound in
  principle, so each one says what it gives up. All of them are switched off by `--exhaustive`
  (`Options.exhaustive`), and `ReductionsTest` checks that they don't change results.
- **Search techniques (S).** They change how the remaining tree is searched. `--exhaustive`
  doesn't turn these off. Where one is approximate, it says so.

Keep this file in step with the code: change a rule here in the same commit that changes it in the
code. Rules that were tried and dropped go under *Rejected*, with their numbers.

## Reductions

### R1. Interchangeable objects are one option

- **Where:** `Equivalence`, used by every decision in `SolverController` (priority, targets, attacks,
  blocks, damage division, trigger order, card choices, opening hand).
- **Rule:** Two options are merged when they differ only in which of several interchangeable
  objects they use. Two cards are interchangeable when their `Equivalence.state` matches:
  name, zone, controller, owner, token, face down, card state, counters. On the battlefield, also
  tapped, summoning sick, entered this turn, phased out, damage, current P/T, type, color,
  sorted keywords, and the defender it's attacking. Players are told apart by seat, and spells
  on the stack by id.
- **Never merged (the card keeps its id):** a card that anything refers to. That covers being
  exiled with something, remembered, imprinted, chosen, encoded, must-block, a gain-control target,
  paired, haunted, attached or with attachments, the source or a target of anything on the stack,
  and (in `key`) any blocking or blocked creature. Creatures in the same combat role (blocking the
  same attacker) are compared by state only (`keyAmongCombatants`).
- **Gives up:** nothing, as long as `state` captures everything that matters. If a card property
  that matters isn't in `state`, merging becomes unsound. The fix is to add the property, or to
  treat the card as referenced.

### R2. Repeated priority windows are skipped

- **Where:** `SolverController.repeatsEarlierWindow`, checked only when the stack is empty.
- **Rule:** Nobody gets priority:
  - in the **draw step** when it comes right after upkeep and no player drew (empty library, since
    patch 0001 removes decking): it's the upkeep again;
  - in the **end of combat step** right after the combat damage step.
- **Gives up:** nothing. Anything done there could be done with the same result in the window
  before.

### R3. Mana abilities only when they do more than make mana (Sid's rule)

- **Where:** `ManaTaps.hasSideEffect`, applied in `SolverController.candidateAbilities`.
- **Rule:** At a priority stop, a mana ability is offered only if:
  - its cost pays life or puts a counter (`CostPayLife`, `CostPutCounter`), or
  - it or one of its sub-abilities deals damage, gains life, or puts a counter on its own
    permanent (`PutCounter` with no `Defined`, or `Defined$ Self`), or
  - its permanent has a `Taps`/`TapsForMana` trigger with `ValidCard$ Card.Self` whose ability
    does one of those things (City of Brass).

  Any other mana source is tapped only while paying a cost. Side-effect mana abilities are
  listed after the real actions (tried later).
- **Gives up:** floating mana in response, such as tapping a land in response to its destruction,
  or tapping your own creature so "destroy target untapped creature" fizzles. Effects of a mana
  ability not listed above (scry, mill, loot, an opponent's life change) are also missed.
- **Measured:** offering every tap and cutting wasted ones at end of step searched 20–35× more
  nodes.

### R4. Opening-hand actions are always used

- **Where:** `SolverController.chooseSaToActivateFromOpeningHand`.
- **Rule:** When there are 1–6 usable actions (Leylines, Chancellors, ...), all of them are used
  and declining isn't searched, unless:
  - any of them costs another card from the hand (`costsACard`: Gemstone Caverns exiles one), or
  - any card in the game can change control of a permanent (`canChangeControl`: a `GainControl`,
    `ExchangeControl` or `ControlSpell` ability, found directly or in SVars).

  In those cases every distinct subset is offered (merged by R1). With more than 6 actions,
  Forge's AI decides.
- **Gives up:** a game where declining an opening-hand action is better for some other reason
  (hiding information doesn't exist here, so this should be rare).

## Bounds on how wide a decision gets

These aren't switched off by `--exhaustive`. They decide what happens when a decision is too big
to enumerate.

### B1. Big choices are made in steps

- **Where:** `SolverController.pick`, `MAX_OPTIONS = 64`, `MAX_CHOICE = 4096`.
- **Rule:** A choice with more than 64 options becomes a choice of range (groups of 64,
  64², ...) and then a choice within it. The options are the same, but each branch point is
  smaller. Choices with more than 4096 options go to Forge's AI. Subsets of many cards are picked
  one card at a time.

### B2. Too many combat assignments go to the AI

- **Where:** `declareAttackers` / `declareBlockers`.
- **Rule:** If (defenders + 1)^attackers (or (attackers + 1)^blockers) is more than 64 × radix,
  Forge's AI declares instead. Patch 0005 makes that deterministic.
- **Gives up:** optimal play in very wide combats.

### B3. Limits on X and targets

- **Rule:** X is offered up to `MAX_X = 20`, and target combinations up to `MAX_TARGETINGS = 512`.

### B4. A one-option decision isn't a branch

- **Where:** `DecisionTape.decide`.
- **Rule:** It's counted (for ordinals) but returns at once and never reaches the search.

## Search techniques

### S1. Null-window alpha-beta, two passes

- **Where:** `Solver.rootSearch` / `expand`.
- **Rule:** For a deterministic game, one pass asks "can A force a win?" and a second asks "can B
  force a win?". Values are in [-1, 1].

### S2. Expectimax at chance nodes

- **Where:** `Solver.chance`.
- **Rule:** Each random draw while something resolves is a chance node with equally likely
  outcomes. Every outcome is searched with a full window, and the results are averaged. There's
  no pruning at chance nodes.

### S3. Transposition table

- **Where:** `Solver.expand`, key from `SolverController.position`.
- **Key:** Forge's `GameState` dump without the turn number, plus phase, active player, deciding
  player, each player's spells and lands this turn, graveyard size, each permanent's
  `Equivalence.state` (sorted), combat assignments by card id, decision kind, and option labels.
- **Rule:** Entries store value, bound (exact/lower/upper) and best move. An entry is used when it's
  exact or its bound settles the window. Otherwise its best move is tried first.
- **Approximate:** the turn number isn't in the key, so "do it now" and "do it a turn later" merge.
  This can misjudge games decided near the turn limit. `--exact-turns` adds the turn to the key.

### S4. Repetition is a draw

- **Where:** `Solver.search` (the `path` set) and `DecisionTape.checkpoint`.
- **Rule:** A position that recurs on the current line is a draw (0). This is checked at every
  decision point. Inside a replay it's also checked once per turn, at the active player's first
  main phase with an empty stack, so a game with no decisions left stops instead of running to
  the turn limit.
- **Approximate:** repetition draws can leak into table entries reused on other lines (the "graph
  history" problem).

### S5. Move ordering

- **Where:** `Solver.moveOrder`, and the order of options in `SolverController`.
- **Rule:** The table's best move comes first, or the remembered best move for that key. After it
  comes the controller's order:
  - priority: actions, then side-effect mana abilities (R3), then Pass, which is always last
    (`Branch.step` relies on that);
  - attacks: most attackers first, no attack last;
  - blocks: most blockers first, no blocks last.

### S6. Turn limit and deepening

- **Where:** `Solver`, `Options.maxTurns` / `firstTurnLimit` / `jumpToLimit`, `EXTEND_FACTOR = 4`.
- **Rule:** A game still running at the turn limit is a draw (default 25 turn cycles). With
  `--first-turns`, the limit starts lower and doubles up to `maxTurns`. `jumpToLimit` goes straight
  to `maxTurns` after the first search. A draw that only came from the limit is searched further,
  up to 4× `maxTurns`, and a win found there is the result.

### S7. Narrowed proof searches (off by default)

- **Where:** `Solver` (`Options.narrowing`, `NARROW_WIDTH = 3`, `NARROW_SHARE = 0.2`).
- **Rule:** Before each full pass, the solver tries to settle it with one side narrowed to its
  first 2 options plus the last (Pass / no attack / no blocks) at priority, attack and block
  decisions:
  - with X's opponent narrowed, X still not winning proves X can't win;
  - with X narrowed, X winning proves X can.

  Each try gets 20% of the remaining budget. A try that never cut anything counts as the full
  search. This is sound (`NarrowingTest`).
- **Status:** off. On 20 hard games it proved nothing more, because those games are deep rather
  than wide.

### S8. Partial results

- **Rule:** When the budget runs out, a pass that was refuted without any line reaching the turn
  limit still proves that side can't force a win. The result is reported as "A can't lose" or "B
  can't lose".

### S9. Resuming paused games

- **Where:** `Solver.resume`, `Continuation`.
- **Rule:** A node's first child continues the paused game instead of replaying it from turn 1.
  Later children replay from the tape. This changes speed only, not the nodes searched.

## Rejected

- **Killer moves** (try the option that last caused a cutoff at the same depth): searched 2–3× more
  nodes. Decision depths don't line up across lines.

## Ideas to evaluate

The hard unsolved games are deep (no repeated position in up to 99 turns), not wide.

- Stronger position abstraction for S3/S4, so near-identical positions (such as life totals that
  no longer matter) merge.
- Recognizing earlier that nothing meaningful is left to do.
- Heuristic move ordering beyond the controller's order (S5).
- Extending R3 to the side effects it doesn't cover yet (scry, loot, opponent's life).
