# 3 Card Blind: notes for working on this repo

README.md covers the rules, usage and how the solver works. This file is what you need to change
it safely.

## Build and test

- Rebuild with `./build.sh`. It skips Forge unless `forge/` changed (`--full` forces), and refuses
  while `./local_server.sh`, `./tcb-web` or `./tcb` runs from this checkout, since the build
  replaces the jars under them (FORCE=1 overrides; don't use it unasked). Raw
  `mvn install` has no such guard. The user restarts the web UI with `./local_server.sh restart`,
  which rebuilds first if sources changed.
- `mvn compile` and `mvn test` only write `*/target/classes`, so they're safe while the server
  runs. To try a change then, run a second server from the classes (they win over the jars in
  `lib/`): `java -Xss512m -Dtcb.forgeGuiDir=$PWD/forge/forge-gui -cp
  "web/target/classes:core/target/classes:web/target/lib/*" tcb.web.WebServer --port 8099
  --entries <scratch> --history <scratch> --no-cache`.
- Tests: `mise exec -- mvn -B -o test -pl core` (about 4 minutes; `ReductionsTest` is most of it).
  Run one class with `-Dtest=AiDecisionsTest`.
- Web UI checks: the headless shell can't click, so serve `index.html` from source with a script
  injected (a small proxy that passes `/api` through to the test server) and screenshot that. Use
  a tall `--window-size` instead of `scrollIntoView`, which garbles headless screenshots. Read the
  console with `--enable-logging=stderr --v=0 --dump-dom`.
- In `index.html`, a top-level `let history` holds the saved tournaments and hides the browser's
  `history`, so use `window.history`.
- The entry search block in `index.html` (between `// ---- entry search` and
  `// ---- end entry search`) has no DOM access on purpose: extract it and test queries in Node.
- Commits: one per issue, `resolves #N: <issue title>`. The user pushes.
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
  Per-thread state is invisible to work Forge hands to its own threads, so a game must stay on one
  thread (patch 0005). `DeterminismTest.parallelSolvesAgree` is the test that catches leaks: it
  reproduced issue #6 once the id-counter race was widened with a `Thread.yield()`.

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
- Watching a search must not change it. The live search line (`Solver.progress`) and tree
  recording (`SearchTree`) are passive; `SolverTest.recordsTheSearchedTree` checks the node count
  is unchanged.
- **Turns:** the solver, `Options.maxTurns` and `GameResult.provenTurnLimit` count Forge turns
  (each player's turn). Everything shown counts turn cycles from 1, converted with `Turns`, so a
  shown limit of 25 is `maxTurns` 50. Web options JSON has `turns` (cycles). History saved before
  this change has `maxTurns` (Forge turns), and the UI converts it.

## Saved formats (changing them breaks existing files)

- Trace lines (`"<A|B|Chance> <kind>: <label>"`) are saved as each game's optimal line, and
  "Watch the game" replays them by matching the text (`DecisionTape.following`). Changing option
  labels makes older saved games unreplayable (the server answers 409 for them).
- Entry files: deck file syntax plus `# created:` / `# updated:` / `# notes:` comment lines at the
  top (`EntryFile`); `DeckList` ignores comments, so the CLI still reads them. The import/export
  format (`EntryImport`) is blank-line-separated blocks: a name, then cards.
- History: `history/<id>.json`, plus `history/<id>.trees/<k>.json.gz` (gzipped `SearchTree` JSON
  for the k-th game, served as-is) when trees were saved.

## Checking a change

- Results: run `./tcb tournament examples/decks --no-cache --json out.json` before and after a
  change, and compare every game's pWinA/pDraw/pWinB. All 42 should match unless the change is meant to fix a
  result.
- The result cache (`cache/`, `ResultCache`) is keyed on a hash of the Forge and `tcb-core` class
  files, so a code change invalidates it, but use `--no-cache` for comparisons and timings anyway.
  If something other than those classes starts to affect results, add it to the key.
- Speed: benchmark matchups are Goblin Guide vs Swords (`examples/decks`) and Memnite vs
  Treetop/Putrefy (`examples/bench`). Compare node counts as well as time.
- Profiling: this machine has a JRE without `jfr`/`jcmd`/`javac`. Record with
  `TCB_JAVA_OPTS="-XX:StartFlightRecording=filename=x.jfr,settings=profile"` and read the file
  with a small `jdk.jfr.consumer` program run as `java Prof.java` (source launch works). Search
  stacks are deep, so JFR truncates the top frames.
- Things that looked promising and weren't: killer moves (2-3x more nodes).
