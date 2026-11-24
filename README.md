# 3 Card Blind: MtG Edition

A deck-building format for Magic: The Gathering. You choose three cards, and a game-tree solver
works out the result of every matchup. The solver plays both sides optimally, so what you're
judged on is the deck you chose, not how well you play.

## Rules

- Each player submits a deck of **exactly 3 Vintage-legal cards**. The Vintage banned list
  applies, but the restricted list doesn't: **any card can be played in multiples**, restricted
  ones included (three Black Lotus is a legal deck).
- The game starts with all 3 cards in your opening hand and nothing in your library. There are
  no mulligans, and both players see everything.
- **You can't lose by drawing from an empty library.** Your draw step just does nothing.
- Every pair of decks plays **two games**, one with each deck on the play.
- Each game is decided by perfect play from both sides:
  - Players maximise P(win) − P(loss).
  - Random events (coin flips, dice, random discard, shuffles) are averaged over every outcome.
  - A game that can loop forever is a **draw**, and so is a game still going at the turn limit
    (25 turns by default). A deck that wins slowly isn't cut off by the limit, though: a draw that
    only comes from the limit is searched further, doubling the limit up to 4 times its value
    (100 turns by default), and a win found there counts. If the time or node budget runs out
    first, the draw is marked unproven.
- Turns are numbered from 1 and count turn cycles: turn 3 is each player's third turn, so a
  result like "wins on turn 3 (B's turn)" names whose half of the turn it was.
- Scoring per game: **win 3, draw 1, loss 0**. For games involving randomness the score is the
  expectation, 3·P(win) + P(draw). The deck with the most points wins (more wins breaks a tie).
- Or **win 2, draw 1, loss 0**: [3cardblind.com's scoring](https://www.3cardblind.com/format-info/rules)
  (a win 1, a draw 0.5) with whole numbers. Standings then show each deck's points as a
  percentage of the points it could have scored. A draw is worth half a win here, not a third, so
  the two can rank the same results differently. Scoring doesn't change any game: the web UI's
  *Scoring* setting re-scores saved tournaments too, and the CLI takes `--scoring 2-1-0`.

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
./local_server.sh          # builds if the code changed, then serves http://localhost:8642
./local_server.sh -p 3000  # another port; also: status, stop, restart
```

It runs in the foreground (Ctrl-C stops it) and passes `--entries`, `--history`, `--cache`,
`--no-cache` and `--threads` on to the server. `./tcb-web` starts the server without the checks.

Open http://localhost:8642 to add entries (with card-name suggestions and legality checks),
run the tournament, and browse the standings, the matchup matrix and the optimal line of every
game. Entries are saved as deck files in the `--entries` folder, so the CLI can run the same
field: `./tcb tournament entries`. The first time it runs (no `--entries` folder yet), it starts
the folder with the sample decks in `examples/entries`. An entry can have notes, and shows when it
was added and last edited (*Edit* changes its cards, name or notes); these are kept as
`# created:` / `# updated:` / `# notes:` comment lines at the top of its deck file, which the CLI
ignores. With the name left blank, the form suggests two names from the cards once all three
match (cards in alphabetical order: the first 4 letters of each, or the first 2 and last 2, so
Goblin Guide, Mountain, Mountain is `GoblMounMoun` or `GodeMoinMoin`); click one to use it.
*Import or export entries* (under the form) adds many entries from pasted text or a
`.txt` file, with a preview of what each will do first, and saves every entry to one file in the
same format: blank lines between entries, each a name (or `Name: ...`) followed by its cards and
any `# notes:` lines. An entry can also be one line, cards separated by `|`, with an optional
name and comma first: `Sligh, Goblin Guide | 2 Mountain` (a comma only ends the name if a card
follows it, so `Khan, Vengeful Heart | Island | Island` is three cards). An entry without a name gets the form's first suggested name (`GoblMounMoun`), with a number added if an entry with other cards already has that name. `./tcb tournament --from-file FILE` runs a field from such a file. The search box above the list
filters the entries with Scryfall-style syntax (`t:creature -c:u`, `lands>=2`, `card="black lotus"`,
`added>=2026-10`, `or` and parentheses; *Search syntax* lists it all), keeps the query in the URL,
and the card view's *Entries with this card* searches for that card. Tick the entries to include to run a
tournament on just some of them. Each finished tournament is saved as a JSON file in the
`--history` folder (default `history`); *Tournament* above the results picks one (newest first,
with ‹ › to step through them) or deletes it. A tournament's results are in three tabs:
*Standings* (click a deck for its games), *Matchups* (the points table; click a cell for that
pair's games) and *Games*, which filters the games by deck, opponent, result (from the chosen deck's
side) and words in the decks' names, 50 at a time; each game's row has *▶ Watch*, and opening it
shows the optimal line. The tournament, tab, filter and the game being watched are kept in the
URL's hash (`#t=<id>&tab=games&deck=…&game=7`), so a link opens the same view (on the public site
too) and Back closes a replay. On a wide screen the entries column scrolls on its own; on a phone
the results come first. *Search settings* (next to *Run tournament*) set the time and node budget per game, the turn
limit and exact turns; they default to the CLI's defaults. The web UI first searches each game to
turn 2, then at the full limit, so a game that runs out of budget still has a result (proven to
turn 2) and a line to watch; that first search costs about 15% more nodes. *Stop* ends a running tournament early:
finished games are kept and saved, and the rest are listed as not played. Click a card in an
entry's hand or in the standings to see its Scryfall scan, rules text and format legality (fetched
from the Scryfall API in the browser; click a double-faced card's scan to turn it over).

### Public site

`site/` publishes the results as a read-only website: anyone can browse the saved tournaments,
watch the games and browse saved search trees, and ask for a deck or a matchup to be solved.
Solving stays on this machine; the site has no Forge and no solver.

```sh
cp site/env.example site/.env   # fill in the server, domain and a request key
site/deploy.sh --dry-run        # export history/ into site/public/, show what would change
site/deploy.sh                  # export and upload
site/deploy.sh --requests       # also install or update the request service
site/requests.sh                # list the waiting requests, saving their decks to import
site/requests.sh solved ID "In the Nov 22 tournament"   # or: declined ID [reply], waiting ID
```

- **Export** (`tcb.web.SiteExport`, run by `deploy.sh` from the built jars): writes `index.html`
  in its public mode (`<html data-mode="public">`: no entries, no running tournaments, no deleting)
  and static JSON in `site/public/data/` — the list of tournaments, each saved file, every game's
  replay (gzipped; the page unzips them) and, with `-- --trees`, the search trees. Replays are made
  here, since the site can't run Forge: a game whose line the current engine no longer plays the
  same way gets no replay and says so. An unchanged tournament keeps its replays from the last
  export. `-- --only ID,ID` publishes only those tournaments.
- **Requests** (`site/requests/server.js`, Node with no dependencies, behind nginx at
  `/api/requests`): visitors send a deck, or a matchup of two, with an optional note. It checks the
  cards against the exported card list, merges a request already waiting, limits requests per
  visitor per day (by a hash of the address, which isn't stored), and lists them on the site
  without their notes. `site/requests.sh` collects them with the key in `site/.env`: it saves all
  of them in `requests/requests.json` and the waiting decks in `requests/waiting.txt`, one line
  per deck in the import format, ready to paste into *Import or export entries*.
- **Server**: the site is https://mtg.jefamirault.com/3cb/ (the MTG apps each have a path on
  `mtg.jefamirault.com`). `site/nginx-mtg-3cb.conf` is an nginx snippet that the
  `mtg.jefamirault.com` server block includes; it serves `/var/www/3cb` at `/3cb/` and proxies
  `/3cb/api/requests` to the request service, which `deploy.sh --requests` installs as the deploy
  user's systemd user unit `3cb-requests`. The page uses relative paths only, so it works under any
  path.

### CLI

`tournament` takes deck files or directories of `.txt` decks. It prints each result as it's
found, then a matrix showing the points each deck scored against each other deck, then the
standings. `--json` also writes the optimal line for every game. `--scoring` picks `3-1-0`
(default) or `2-1-0`.

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
| `--max-turns` | 25 | Turn limit, in turns of each player; a game still going counts as a draw, unless searching up to 4 times the limit finds a win |
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
budget it has used. Expanding a game shows the line its search is on: each decision from the start
of the game, the option being tried there and how many of its options have been tried (the first
30 decisions; the deeper ones change too fast to follow).

*▶ Watch* (on each finished game's row) replays its optimal line on an animated board: both
players' hands, battlefields, graveyards and exile, the stack, life totals, and the turn and phase,
with play/pause, stepping (arrow keys) and a scrubber. *Previous game* / *Next game* (P / N) go
through the games the Games tab's filter shows, without closing the board. The server replays the saved line in Forge
and snapshots the board at the end of each step of each turn (`GameReplay`), so tournaments saved
earlier can be watched too. It advances one step of the turn at a time (untap, upkeep, draw, main
phase, the combat steps, and so on), listing the actions taken in that step; steps the rules skip
(the blockers and damage steps when nothing attacks) aren't shown, and when playing, a step where
nothing happens is shown only briefly. Passes are listed only if *Show passes* is ticked. Hovering a card shows
it full size, and *Card size* scales the board (remembered per browser).

*Save search trees* (in *Search settings*, off by default, up to 8 entries) keeps each game's
searched tree, so a finished game can be browsed one decision at a time: every option, what the
search proved about it and which was best. Values are what alpha-beta proved, so many are bounds
("swords can't win") rather than exact, and options it skipped are marked. Each search pass keeps
at most 50,000 nodes, saved gzipped in `history/<id>.trees/`. Cached games are solved again, since
the cache has no trees.

The *Stats* tab (`#stats`) shows what the app stores and the space it takes: entries, saved
tournaments, their search trees and the result cache, split into current and stale (written by an
older build, so it can't be hit again; nothing deletes these on its own). It also has the
most-used cards, games solved and proven, the slowest game, and cache hits since the server
started. From there you can clear the stale cache files and delete a tournament's search trees
while keeping its results.

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
  - which permanents pay a mana cost, and tapping for mana at a priority stop when the ability
    does something besides making mana (Pristine Talisman's life; see *Mana abilities* below)
  - attack and block assignments, and how combat damage is divided among blockers (and trample)
  - leylines and other opening-hand actions
  - optional costs and "may" abilities
  - numbers, modes and card choices. Choices with more than 64 options are made in steps
    (first a range, then an option in it; or one card at a time).

  An unexplored decision stops the game with a signal, so the search can expand it.
- **Random events** pass through a replaceable random source (`ChanceRandom`). While a spell or
  ability resolves, each random draw becomes a chance node.
- **Fewer branches.** (Every rule, exactly: [docs/search-rules.md](docs/search-rules.md).) Options that differ only in which of several identical cards they use
  (which Memnite attacks, which identical creature a removal spell hits) are one option. A card
  that anything refers to (attachments, remembered cards, targets on the stack, ...) is never
  merged. The draw step when nobody drew, and the end of combat step right after combat damage,
  offer nothing the step before didn't, so nobody acts there.
- **Mana abilities (Sid's rule).** Tapping a permanent for mana that then goes unspent gains
  nothing, unless the ability also does something else. So a mana ability is offered at a
  priority stop only if it puts a counter on its own permanent, deals damage, has a life payment
  or makes a player gain life, or the permanent has a "whenever this becomes tapped" trigger that
  does (City of Brass). Every other mana source is tapped only while paying a cost. (Offering
  every tap and cutting the wasted ones at the end of the step searched 20 to 35 times more
  nodes.)
- **Opening-hand actions.** Leylines, Chancellors and the like are always used: declining isn't
  searched, unless a card in the game can change control of a permanent (a Leyline the opponent
  could take is a reason to keep it in hand) or the action costs another card from the hand
  (Gemstone Caverns exiles one, and Forge's AI picks which).

  `--exhaustive` turns these reductions off, and the tests check they don't change results.
- **Search.**
  - Deterministic games: two null-window alpha-beta passes ("can A force a win?", then "can B
    force a win?"). These are fast because most 3CB games are draws.
  - **Narrowed searches first.** A proof that X wins only needs X's moves to be good, and X's
    opponent's moves all covered; a proof that X can't win is the other way round. So before
    each full pass, the solver tries to settle the question with one side's options cut down:
    at its priority, attack and block decisions that side keeps only the first two options and
    the last (Pass, no attack, no blocks). With X's opponent narrowed, X still not winning
    proves X can't; with X narrowed, X winning proves X can. Each try may use a fifth of the
    remaining budget, and a try that never had to leave anything out counts as the full search.
    Otherwise the full pass runs as before. This never changes a result (`NarrowingTest`), but
    it's **off by default** (`Options.narrowing`): on 20 hard games of a 28-entry tournament
    (120 s each) it proved nothing more, since those games are deep (no position repeats over
    up to 99 turns) rather than wide, and its tries cost budget.
  - **Partial results.** When the budget runs out, a pass that was refuted without any line
    reaching the turn limit still proves that side can't force a win, so an unproven game can
    say "A can't lose" or "B can't lose" (in the CLI, the tournament log, the results JSON as
    `cantLose`, and the web UI).
  - Games with randomness: a full expectimax search.
  - Both use a transposition table keyed on Forge's own game-state dump, plus each permanent's
    current characteristics (the dump leaves out temporary changes, such as an animated land).
  - A position that repeats along a line counts as a draw. This is checked at decision points,
    and once per turn inside each replay, so a game with no meaningful decisions left ends
    quickly instead of running to the turn limit.
- **Patches to Forge** (`patches/`, applied by `build.sh`):
  1. a `GameRules` flag that turns off the empty-library loss (rule 704.5b);
  2. `MyRandom.setRandomDirect`, so randomness can be intercepted (stock `MyRandom` is already
     per-thread, but wraps every source in a counter that hides overridden `nextInt(bound)` etc.);
  3. per-thread, resettable id counters for abilities, triggers and effects. The stock counters
     are shared, non-atomic statics, so parallel games could hand out duplicate ids. Resetting
     them before every replay also keeps replays identical.
  4. a per-thread `AiCache`. The stock cache is global and only cleared inside the AI's own
     priority decision, which the solver replaces, so it kept every game ever played in memory.
  5. the AI's forced-attacker check runs in order on the game thread instead of on a thread pool
     with a timeout, so the attack the AI falls back to (more than 64 possible assignments) is the
     same on every replay.

  The solver also sets each game's `AI_TIMEOUT` to a day, so the decisions left to Forge's AI
  never stop at its wall-clock deadline and depend on machine load.

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

Tests: `mise exec -- mvn test` (about five minutes). Tests tagged `@Tag("slow")` (long exhaustive
comparisons) run only with `-Pslow`.

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
  - Plain mana sources are only tapped while paying, so floating mana in response to something
    (tapping a land in response to its destruction, or tapping a creature so "destroy target
    untapped creature" fizzles) isn't searched.
  - Always using opening-hand actions misses the rare game where declining is better for some
    other reason.
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
  printings are acorn/Un-cards (for example Blacker Lotus's Secret Lair printing). Digital-only
  cards aren't legal: Forge's Vintage sets are the paper ones, Arena's rebalanced cards ("A-"
  names) are excluded, and cards Forge has a script for but lists in no set aren't loaded.
- **Rules accuracy is Forge's.** A card that is scripted wrong in Forge is played wrong here.
- **Speed.** Replaying a node costs a few milliseconds plus about 1 ms per turn played, so decks
  with many options at instant speed take longest. Most matchups finish in seconds to a minute
  on one core; the example tournament takes under a minute on 32 cores.
