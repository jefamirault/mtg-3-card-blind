# 3 Card Blind: MtG Edition

A deck-building format for Magic: The Gathering. You choose three cards, and a game-tree solver
works out the result of every matchup. The solver plays both sides optimally, so what you're
judged on is the deck you chose, not how well you play.

## Rules

- Each player submits a deck of **exactly 3 Vintage-legal cards**. The Vintage banned and
  restricted lists apply: at most one copy of a restricted card. Basic lands are unlimited.
- The game starts with all 3 cards in your opening hand and nothing in your library. There are
  no mulligans, and both players see everything.
- **You can't lose by drawing from an empty library.** Your draw step just does nothing.
- Every pair of decks plays **two games**, one with each deck on the play.
- Each game is decided by perfect play from both sides:
  - Players maximise P(win) − P(loss).
  - Random events (coin flips, dice, random discard, shuffles) are averaged over every outcome.
  - A game that can loop forever is a **draw**, and so is a game still going at the turn limit
    (50 turns by default).
- Scoring per game: **win 3, draw 1, loss 0**. For games involving randomness the score is the
  expectation, 3·P(win) + P(draw). The deck with the most points wins.

## Usage

Requires Java 17+ and Maven ([mise](https://mise.jdx.dev) users: `mise install` sets up the pinned Maven).

```sh
./build.sh                 # patch + build Forge into ~/.m2 (skipped if unchanged), then this project
./tcb validate examples/decks
./tcb solve examples/decks/tusker.txt examples/decks/counterspell.txt
./tcb tournament examples/decks --json results.json
```

Deck files are plain text, one card per line with an optional count. `#` starts a comment:

```
# Ritual Specter
Swamp
Dark Ritual
Hypnotic Specter
```

### Web UI

```sh
./local_server.sh          # builds if the code changed, then serves http://localhost:8080
./local_server.sh -p 3000  # another port; also: status, stop, restart
```

It runs in the foreground (Ctrl-C stops it) and passes `--entries`, `--history`, `--cache`,
`--no-cache` and `--threads` on to the server. `./tcb-web` starts the server without the checks.

Open http://localhost:8080 to add entries (with card-name suggestions and legality checks),
run the tournament, and browse the standings, the matchup matrix and the optimal line of every
game. Entries are saved as deck files in the `--entries` folder, so the CLI can run the same
field: `./tcb tournament entries`. The first time it runs (no `--entries` folder yet), it starts
the folder with the sample decks in `examples/entries`. Tick the entries to include to run a
tournament on just some of them. Each finished tournament is saved as a JSON file in the
`--history` folder (default `history`); the page lists them, newest first, and lets you view or
delete each one. *Search settings* (next to *Run tournament*) set the time and node budget per game, the turn
limit and exact turns; they default to the CLI's defaults. *Stop* ends a running tournament early:
finished games are kept and saved, and the rest are listed as not played. Click a card in an
entry's hand or in the standings to see its Scryfall scan, rules text and format legality (fetched
from the Scryfall API in the browser; click a double-faced card's scan to turn it over).

### CLI

`tournament` takes deck files or directories of `.txt` decks. It prints each result as it's
found, then a matrix showing the points each deck scored against each other deck, then the
standings. `--json` also writes the optimal line for every game.

Solved games are saved in a cache folder (`--cache`, default `cache`), one JSON file per game,
and later tournaments reuse them, so adding an entry only solves the new pairings. A game is
keyed on both decks' cards (not their names or card order), who's on the play, `--max-turns`,
`--exact-turns`, `--exhaustive` and a fingerprint of the compiled Forge and solver code (plus the
Forge commit), so rebuilding after any code change starts afresh. A result that hit its budget is
reused only by runs with no bigger a budget. `--no-cache` solves every game and saves nothing. The
web UI uses the same cache (`./tcb-web --cache DIR`, or `--no-cache`) and marks reused games as
*cached*.

Search options shared by `solve` and `tournament`:

| Option | Default | Meaning |
|---|---|---|
| `--max-turns` | 50 | Turn limit (counting both players' turns); a game still going counts as a draw |
| `--nodes` | 2,000,000 | Search nodes per game before giving up |
| `--time` | 10m | Time per game before giving up |
| `--first-turns` | off | Iteratively deepen the turn limit from here (doubling), so a game that runs out of budget still has a result proven to a shorter limit |
| `--exact-turns` | off | Turn-aware transposition table (see limitations) |
| `--exhaustive` | off | Branch on every option: don't merge options that only swap identical cards, or skip repeated priority windows (slower; for checking results) |
| `--threads` | CPUs | Games solved in parallel (`tournament` only) |

Results that hit the budget are marked "unproven beyond turn N". A game whose replays stop being
deterministic (a bug: usually Forge state shared between parallel games) is solved again with no
other game running; if that fails too, it's scored as an unproven draw. While a tournament runs,
the web UI shows each game being solved with its node count, elapsed time and how much of its
budget it has used.

## How it works

The rules engine is [Forge](https://github.com/Card-Forge/forge) (GPL-3.0). It's included as a
submodule pinned to a fixed commit and has scripts for nearly every Magic card.

- **Replays instead of copies.** Each search node is a list of decisions from the start of the
  game. To expand a node, the solver starts a fresh, seeded Forge game and replays that list.
  This uses Forge's real game loop, so the state is always exact. Forge's own game-copying code
  drops parts of the state, such as abilities on the stack.
  - Each game runs on its own thread and *pauses* at the first new decision. The first option
    explored there continues the paused game; only the other options replay from turn 1.
  - A replay doesn't list the options of decisions where the recorded choice is "Pass" (most of
    them), which skips most of the mana and target checks.
- **Every choice is a branch.** `SolverController` is a Forge player controller that turns each
  decision into a branch point. That covers:
  - spells and abilities at every priority stop, with every value of X and every legal set of
    targets (including spells on the stack, so counterspells work, and every way to divide
    "divided as you choose" damage)
  - targets of triggered abilities and of chosen modes, and the order of simultaneous triggers
  - which permanents pay a mana cost
  - attack and block assignments, and how combat damage is divided among blockers (and trample)
  - leylines and other opening-hand actions
  - optional costs and "may" abilities
  - numbers, modes and card choices. Choices with more than 64 options are made in steps
    (first a range, then an option in it; or one card at a time).

  An unexplored decision stops the game with a signal, so the search can expand it.
- **Random events** pass through a replaceable random source (`ChanceRandom`). While a spell or
  ability resolves, each random draw becomes a chance node.
- **Fewer branches.** Options that differ only in which of several identical cards they use
  (which Memnite attacks, which identical creature a removal spell hits) are one option. A card
  that anything refers to (attachments, remembered cards, targets on the stack, ...) is never
  merged. The draw step when nobody drew, and the end of combat step right after combat damage,
  offer nothing the step before didn't, so nobody acts there. `--exhaustive` turns this off, and
  the tests check it doesn't change results.
- **Search.**
  - Deterministic games: two null-window alpha-beta passes ("can A force a win?", then "can B
    force a win?"). These are fast because most 3CB games are draws.
  - Games with randomness: a full expectimax search.
  - Both use a transposition table keyed on Forge's own game-state dump, plus each permanent's
    current characteristics (the dump leaves out temporary changes, such as an animated land).
  - A position that repeats along a line counts as a draw. This is checked at decision points,
    and once per turn inside each replay, so a game with no meaningful decisions left ends
    quickly instead of running to the turn limit.
- **Patches to Forge** (`patches/`, applied by `build.sh`):
  1. a `GameRules` flag that turns off the empty-library loss (rule 704.5b);
  2. a per-thread `MyRandom`, so games can be solved in parallel and randomness can be intercepted;
  3. per-thread, resettable id counters for abilities, triggers and effects. The stock counters
     are shared, non-atomic statics, so parallel games could hand out duplicate ids. Resetting
     them before every replay also keeps replays identical.
  4. a per-thread `AiCache`. The stock cache is global and only cleared inside the AI's own
     priority decision, which the solver replaces, so it kept every game ever played in memory.
  5. the AI's forced-attacker check runs in order on the game thread instead of on a thread pool
     with a timeout, so the attack the AI falls back to (more than 64 possible assignments) is the
     same on every replay.

  The launchers run Java with `-XX:+DisableExplicitGC`: Forge calls `System.gc()` after every
  finished game, which stalls every solver thread.

Layout:

```
core/   tcb.deck (deck lists, Vintage validation), tcb.engine (Forge integration, controller,
        replay tape), tcb.solve (solver, tournament); tests in core/src/test
cli/    the `tcb` command line
web/    the web UI (JDK HTTP server + one static page)
forge/  Forge submodule;  patches/  patches applied to it
examples/decks/  sample decks for the CLI
examples/entries/  starter field for the web UI
```

Tests: `mise exec -- mvn test` (about three minutes).

Benchmarks: `./tcb solve examples/decks/goblin-guide.txt examples/decks/swords.txt` (a draw,
about 1,300 nodes per game) and `./tcb solve examples/bench/memnite.txt examples/bench/treetop.txt`
(identical creatures against a manland, removal and a bounce land at every stop; about 14,000
nodes per game).

## Known limitations

- **Approximations in the search.**
  - By default the transposition table ignores the turn number. This merges positions that
    differ only in timing, which is much faster, but it can misjudge a game decided right at the
    turn limit. Use `--exact-turns` to rule that out.
  - Repetition draws found along one line can leak into table entries reused elsewhere (the
    usual "graph history" caveat).
- **Choices left to Forge's AI.** A few decisions still use the AI's heuristics instead of
  branching:
  - which colour a mana ability produces, and paying with sources that don't tap (e.g. a
    sacrifice-only ability)
  - legacy damage assignment order (off under current rules), and "divided as you choose"
    combat damage (Thorn Elemental and friends)
  - attacks and blocks with more than 64 possible assignments, and modes that can repeat
  - X above 20
- **Mana payment** is branched by setting the other mana sources aside (marked tapped without any
  game event) while Forge's AI pays from the chosen ones. A card that cares how many permanents
  are untapped *during* the payment would see the wrong number.
- **Randomness.**
  - Random events with more than 16 outcomes, and randomness outside resolving spells and
    abilities, use a fixed seed instead of branching.
  - Calling heads or tails is fixed, which can't change a fair flip.
- **Legality.** Forge's Vintage list is used, plus a check that rejects cards whose only legal-set
  printings are acorn/Un-cards (for example Blacker Lotus's Secret Lair printing).
- **Rules accuracy is Forge's.** A card that is scripted wrong in Forge is played wrong here.
- **Speed.** Replaying a node costs a few milliseconds plus about 1 ms per turn played, so decks
  with many options at instant speed take longest. Most matchups finish in seconds to a minute
  on one core; the example tournament takes under a minute on 32 cores.
