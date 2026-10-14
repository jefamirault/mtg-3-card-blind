# 3 Card Blind: notes for working on this repo

README.md covers the rules, usage and how the solver works. This file is what you need to change
it safely.

## Build and test

- `mise exec -- mvn -B -q -o install -DskipTests` rebuilds this project in seconds (offline is fine
  once `./build.sh` has run). `./tcb` and `./tcb-web` run the jars in `cli/target` and
  `web/target`, so rebuild before running them.
- Don't rebuild while a `./tcb` or `./tcb-web` process is running: it replaces the jars under it.
- Tests: `mise exec -- mvn -B -o test -pl core` (about 3 minutes; `ReductionsTest` is most of it).
  Run one class with `-Dtest=AiDecisionsTest`.
- The `forge/` submodule always shows as modified (`m forge`): `build.sh` applies `patches/*.patch`
  to its working tree. That's expected; never commit the submodule pointer for it.

## Changing Forge

- Edit under `forge/`, then save the change as the next numbered patch:
  `git -C forge diff <files> > patches/000N-name.patch`, and list it in the README's patch list.
- Rebuild just the changed module from inside `forge/` (its `.mvn` config uses relative paths):
  `mise exec -- mvn -B -q -o -pl forge-ai flatten:flatten install -DskipTests -Dcheckstyle.skip`,
  then rebuild this project so `*/target/lib` picks up the new jar.
- Forge statics are shared across solver threads. Anything static that a game touches has to be
  per-thread (see patches 0002 to 0004) or it breaks parallel tournaments and determinism.

## Invariants the solver depends on

- **Replays are deterministic.** The same tape must give the same game, whether it's replayed,
  continued from a paused game, or replayed with the "Pass" shortcut. `DeterminismTest` checks all
  three. Run it after any controller or engine change.
- Every decision goes through `DecisionTape.decide` (which counts ordinals), and `"Pass"` is the
  last priority label (`Branch.step` relies on it). A decision that returns early without calling
  `decide` must do so the same way on every replay.
- A new reduction (merging or skipping options) must be switched off by `exhaustive` and covered in
  `ReductionsTest`. `Equivalence` is deliberately conservative: when unsure, keep a card's id.
- A paused game (`Continuation`) must be resumed or abandoned. `Solver.search` abandons them in a
  `finally` block.

## Checking a change

- Results: run `./tcb tournament examples/decks --json out.json` before and after a change, and
  compare every game's pWinA/pDraw/pWinB. All 42 should match unless the change is meant to fix a
  result.
- Speed: benchmark matchups are Goblin Guide vs Swords (`examples/decks`) and Memnite vs
  Treetop/Putrefy (`examples/bench`). Compare node counts as well as time.
- Profiling: this machine has a JRE without `jfr`/`jcmd`/`javac`. Record with
  `TCB_JAVA_OPTS="-XX:StartFlightRecording=filename=x.jfr,settings=profile"` and read the file
  with a small `jdk.jfr.consumer` program run as `java Prof.java` (source launch works). Search
  stacks are deep, so JFR truncates the top frames.
- Things that looked promising and weren't: killer moves (2-3x more nodes).
