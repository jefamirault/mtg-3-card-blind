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
./build.sh                 # patch + build Forge into ~/.m2, then build this project
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
./tcb-web --port 8080 --entries entries
```

Open http://localhost:8080 to add entries (with card-name suggestions and legality checks),
run the tournament, and browse the standings, the matchup matrix and the optimal line of every
game. Entries are saved as deck files in the `--entries` folder, so the CLI can run the same
field: `./tcb tournament entries`.

### CLI

`tournament` takes deck files or directories of `.txt` decks. It prints each result as it's
found, then a matrix showing the points each deck scored against each other deck, then the
standings. `--json` also writes the optimal line for every game.

Search options shared by `solve` and `tournament`:

| Option | Default | Meaning |
|---|---|---|
| `--max-turns` | 50 | Turn limit (counting both players' turns); a game still going counts as a draw |
| `--nodes` | 2,000,000 | Search nodes per game before giving up |
| `--time` | 10m | Time per game before giving up |
| `--first-turns` | off | Iteratively deepen the turn limit from here (doubling), so a game that runs out of budget still has a result proven to a shorter limit |
| `--exact-turns` | off | Turn-aware transposition table (see limitations) |
| `--threads` | CPUs | Games solved in parallel (`tournament` only) |

Results that hit the budget are marked "unproven beyond turn N".

## How it works

The rules engine is [Forge](https://github.com/Card-Forge/forge) (GPL-3.0). It's included as a
submodule pinned to a fixed commit and has scripts for nearly every Magic card.

- **Replays instead of copies.** Each search node is a list of decisions from the start of the
  game. To expand a node, the solver starts a fresh, seeded Forge game and replays that list.
  This uses Forge's real game loop, so the state is always exact. Forge's own game-copying code
  drops parts of the state, such as abilities on the stack.
- **Every choice is a branch.** `SolverController` is a Forge player controller that turns each
  decision into a branch point. That covers:
  - spells and abilities at every priority stop, with every legal set of targets (including
    spells on the stack, so counterspells work)
  - attack and block assignments
  - optional costs and "may" abilities
  - numbers, modes and single-card choices

  An unexplored decision stops the game with a signal, so the search can expand it.
- **Random events** pass through a replaceable random source (`ChanceRandom`). While a spell or
  ability resolves, each random draw becomes a chance node.
- **Search.**
  - Deterministic games: two null-window alpha-beta passes ("can A force a win?", then "can B
    force a win?"). These are fast because most 3CB games are draws.
  - Games with randomness: a full expectimax search.
  - Both use a transposition table keyed on Forge's own game-state dump.
  - A position that repeats along a line counts as a draw. This is checked at decision points,
    and once per turn inside each replay, so a game with no meaningful decisions left ends
    quickly instead of running to the turn limit.
- **Patches to Forge** (`patches/`, applied by `build.sh`):
  1. a `GameRules` flag that turns off the empty-library loss (rule 704.5b);
  2. a per-thread `MyRandom`, so games can be solved in parallel and randomness can be intercepted;
  3. per-thread, resettable id counters for abilities, triggers and effects. The stock counters
     are shared, non-atomic statics, so parallel games could hand out duplicate ids. Resetting
     them before every replay also keeps replays identical.

Layout:

```
core/   tcb.deck (deck lists, Vintage validation), tcb.engine (Forge integration, controller,
        replay tape), tcb.solve (solver, tournament); tests in core/src/test
cli/    the `tcb` command line
web/    the web UI (JDK HTTP server + one static page)
forge/  Forge submodule;  patches/  patches applied to it
examples/decks/  sample entries
```

Tests: `mise exec -- mvn test` (the solver tests take a few minutes).

## Known limitations

- **Approximations in the search.**
  - By default the transposition table ignores the turn number. This merges positions that
    differ only in timing, which is much faster, but it can misjudge a game decided right at the
    turn limit. Use `--exact-turns` to rule that out.
  - Repetition draws found along one line can leak into table entries reused elsewhere (the
    usual "graph history" caveat).
- **Choices left to Forge's AI.** These still use the AI's heuristics instead of branching:
  - targets of *triggered* abilities
  - X values when casting
  - mana payment (which permanents to tap)
  - damage assignment order
  - leylines and other opening-hand actions
  - choices with more than 64 options (for example, picking a subset from a large set)
- **Randomness.**
  - Random events with more than 16 outcomes, and randomness outside resolving spells and
    abilities, use a fixed seed instead of branching.
  - Calling heads or tails is fixed, which can't change a fair flip.
- **Legality.** Forge's Vintage list is used, plus a check that rejects cards whose only legal-set
  printings are acorn/Un-cards (for example Blacker Lotus's Secret Lair printing).
- **Rules accuracy is Forge's.** A card that is scripted wrong in Forge is played wrong here.
- **Speed.** Each node costs a game replay (a few milliseconds), so decks with many options at
  instant speed take longest. Most matchups finish in seconds to a minute on one core.
