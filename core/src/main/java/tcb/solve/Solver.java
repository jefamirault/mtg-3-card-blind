package tcb.solve;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import tcb.deck.DeckList;
import tcb.engine.Continuation;
import tcb.engine.DecisionTape;
import tcb.engine.DecisionTape.Step;
import tcb.engine.GameRunner;
import tcb.engine.GameRunner.Outcome;
import tcb.engine.Signals.Branch;

/**
 * Solves a single 3 Card Blind game (fixed decks, fixed starting player) by searching the game
 * tree over Forge replays: minimax with alpha-beta pruning at player decisions, expectation at
 * random events (expectimax).
 *
 * Values are from deck A's point of view: P(A wins) - P(B wins), so +1 win, 0 draw, -1 loss for
 * games without randomness. Each player maximises their own chance of winning minus their chance
 * of losing. Games still running at the turn limit are draws, and so is a position repeating along
 * a line (the players can loop forever).
 */
public final class Solver {

    /**
     * @param maxTurns       turn limit; games still going are draws. A draw that only comes from
     *                       the limit is searched further, up to {@link #EXTEND_FACTOR} times
     *                       maxTurns, and a win found there is the result.
     * @param firstTurnLimit start of iterative deepening (doubling up to maxTurns); equal to
     *                       maxTurns means a single search
     * @param jumpToLimit    after the firstTurnLimit search, go straight to maxTurns instead of
     *                       doubling: one cheap search makes sure there's a result if the budget
     *                       runs out, without paying for every limit in between
     * @param exactTurns     key the transposition table on (position, turn) instead of position
     *                       alone. Turn-free keys merge "do it now" with "do it a turn later",
     *                       which is much faster, but can misjudge games decided near the limit.
     * @param exhaustive     branch on every option: don't merge options that only swap identical
     *                       cards, and search declining opening-hand actions. Slower; for checking
     *                       that those reductions don't change results.
     * @param narrowing      before each full "can X force a win?" search, try to settle it with one
     *                       side's options narrowed (see {@link #NARROW_WIDTH}): with X's opponent
     *                       narrowed, a failure to win still proves X can't; with X narrowed, a win
     *                       still proves X wins. Never changes a result, only how fast it's found.
     *                       Off by default: on 20 hard games of a 28-entry tournament (120 s each)
     *                       it proved nothing more, since they're deep rather than wide, and its
     *                       tries cost budget. Ignored when exhaustive.
     */
    public record Options(int maxTurns, int firstTurnLimit, long nodeBudget, Duration timeBudget, boolean exactTurns,
                          boolean exhaustive, boolean jumpToLimit, boolean narrowing) {
        public Options(int maxTurns, int firstTurnLimit, long nodeBudget, Duration timeBudget, boolean exactTurns,
                       boolean exhaustive, boolean jumpToLimit) {
            this(maxTurns, firstTurnLimit, nodeBudget, timeBudget, exactTurns, exhaustive, jumpToLimit, false);
        }

        public Options(int maxTurns, int firstTurnLimit, long nodeBudget, Duration timeBudget, boolean exactTurns,
                       boolean exhaustive) {
            this(maxTurns, firstTurnLimit, nodeBudget, timeBudget, exactTurns, exhaustive, false);
        }

        public Options(int maxTurns, int firstTurnLimit, long nodeBudget, Duration timeBudget, boolean exactTurns) {
            this(maxTurns, firstTurnLimit, nodeBudget, timeBudget, exactTurns, false, false);
        }

        public static Options defaults() {
            return new Options(50, 50, 2_000_000, Duration.ofMinutes(10), false);
        }

        public Options withExhaustive(boolean exhaustive) {
            return new Options(maxTurns, firstTurnLimit, nodeBudget, timeBudget, exactTurns, exhaustive, jumpToLimit, narrowing);
        }

        public Options withNarrowing(boolean narrowing) {
            return new Options(maxTurns, firstTurnLimit, nodeBudget, timeBudget, exactTurns, exhaustive, jumpToLimit, narrowing);
        }
    }

    /**
     * How many options a narrowed player keeps at a priority, attack or block decision: the first
     * NARROW_WIDTH - 1 in the controller's order (actions before passing, biggest attacks first),
     * and the last (Pass, no attack, no blocks).
     */
    public static final int NARROW_WIDTH = 3;
    /** Share of the remaining budget each narrowed search may use before giving up. */
    static final double NARROW_SHARE = 0.2;

    /**
     * @param pWin              probability deck A wins under optimal play
     * @param pDraw             probability of a draw
     * @param pLoss             probability deck B wins
     * @param provenTurnLimit   largest turn limit fully searched, in Forge turns (0 if not even the
     *                          first finished); {@link #provenTurns()} in turn cycles
     * @param complete          true if the result is final: decisive, independent of the turn limit
     *                          (no game ran into it), or searched up to {@link #EXTEND_FACTOR}
     *                          times maxTurns
     * @param principalVariation the optimal line found, as readable decisions
     */
    public record GameResult(double pWin, double pDraw, double pLoss, int provenTurnLimit, boolean complete,
                             List<String> principalVariation, long nodes, Duration elapsed, Bound bound) {
        public GameResult(double pWin, double pDraw, double pLoss, int provenTurnLimit, boolean complete,
                          List<String> principalVariation, long nodes, Duration elapsed) {
            this(pWin, pDraw, pLoss, provenTurnLimit, complete, principalVariation, nodes, elapsed, Bound.UNKNOWN);
        }

        public GameResult {
            bound = bound == null ? Bound.UNKNOWN : bound;
        }

        /**
         * What an unfinished search proved anyway: a side that can't lose (the other side's "can I
         * force a win?" pass was refuted without any line running into the turn limit). UNKNOWN for
         * a complete result (the result says it all) and for games with random events.
         */
        public enum Bound { UNKNOWN, A_CANT_LOSE, B_CANT_LOSE }

        /** {@link #provenTurnLimit()} in turn cycles, as shown to people. */
        public int provenTurns() {
            return provenTurnLimit / 2;
        }

        /** P(A wins) - P(B wins). */
        public double value() {
            return pWin - pLoss;
        }

        public boolean isRandom() {
            return !(isCertain(pWin) && isCertain(pDraw) && isCertain(pLoss));
        }

        private static boolean isCertain(double p) {
            return p < 1e-9 || p > 1 - 1e-9;
        }

        /**
         * "" for a complete result; otherwise how far it's proven, and what the search proved anyway:
         * " (unproven beyond turn 2; A can't lose)".
         */
        public String unprovenNote() {
            if (complete) {
                return "";
            }
            String can = switch (bound) {
                case A_CANT_LOSE -> "; A can't lose";
                case B_CANT_LOSE -> "; B can't lose";
                case UNKNOWN -> "";
            };
            return " (unproven beyond turn " + provenTurns() + can + ")";
        }

        public String describe() {
            if (isRandom()) {
                return String.format("A wins %.1f%%, draw %.1f%%, B wins %.1f%%", 100 * pWin, 100 * pDraw, 100 * pLoss);
            }
            return pWin > 0.5 ? "A wins" : pLoss > 0.5 ? "B wins" : "draw";
        }
    }

    private static final class BudgetExceeded extends RuntimeException {
        BudgetExceeded() {
            super(null, null, false, false);
        }
    }

    /** A narrowed search used up its share of the budget (the search goes on without it). */
    private static final class AttemptExceeded extends RuntimeException {
        AttemptExceeded() {
            super(null, null, false, false);
        }
    }

    private enum Bound { EXACT, LOWER, UPPER }

    private record Entry(Line line, Bound bound, int best) {
    }

    /** A searched line: its value, the outcome probabilities along it, and the choices. */
    private record Line(double value, double pWin, double pDraw, double pLoss, int[] pv) {
        static Line of(int result) {
            return new Line(result, result > 0 ? 1 : 0, result == 0 ? 1 : 0, result < 0 ? 1 : 0, new int[0]);
        }

        Line withPv(int[] newPv) {
            return new Line(value, pWin, pDraw, pLoss, newPv);
        }
    }

    /**
     * A snapshot of a search in progress, for progress displays.
     *
     * @param nodes      search nodes so far
     * @param elapsed    time since the search started
     * @param turnLimit  turn limit of the current pass in Forge turns (iterative deepening doubles it)
     * @param pass       what the current pass is trying to prove
     * @param budgetUsed fraction of the node or time budget used, whichever is larger (0 to 1)
     * @param line       the decisions on the line being searched right now, from the first one;
     *                   at most {@link #MAX_LINE} of them
     * @param depth      how many decisions deep the search is (can be more than {@code line} holds)
     */
    public record Progress(long nodes, Duration elapsed, int turnLimit, String pass, double budgetUsed,
                           List<Decision> line, int depth) {
    }

    /**
     * One decision on the line being searched.
     *
     * @param player  0 for deck A, 1 for deck B, {@link DecisionTape#CHANCE} for a random event
     * @param kind    decision type, e.g. "priority", "attack"
     * @param turn    turn cycle the decision is made in (see {@link Turns})
     * @param choice  the option being searched
     * @param tried   how many options have been tried here, counting this one
     * @param options how many options there are
     */
    public record Decision(int player, String kind, int turn, String choice, int tried, int options) {
    }

    /**
     * How far past maxTurns the search goes for a game that is a draw only because it was still
     * going at the limit: up to this many times maxTurns.
     */
    public static final int EXTEND_FACTOR = 4;

    /** Most decisions {@link #progress()} reports; the top of the tree changes slowly, the rest flickers. */
    public static final int MAX_LINE = 30;

    private final GameRunner runner;
    private final int startingPlayer;
    private final Options options;
    private final Map<String, Entry> table = new HashMap<>();
    /**
     * Best move found at each position, kept when the table is cleared for a new turn limit or
     * for the expectimax pass: a good first guess there too.
     */
    private final Map<String, Integer> bestMoves = new HashMap<>();
    /** Positions on the current search path, for repetition detection. */
    private final Set<String> path = new HashSet<>();
    private boolean sawChance;
    /** Whether the search for the current turn limit had a game end at that limit. */
    private boolean limitHit;
    /**
     * Proven so far, at any turn limit: A (B) can't force a win, because that side's pass was
     * refuted without any line ending at the turn limit. Kept when the budget runs out later.
     */
    private boolean aCantWin, bCantWin;
    /** Whether any pass met a random event: then the null-window passes don't bound the result. */
    private boolean sawChanceEver;
    /** The player narrowed in the current search (see {@link Options#narrowing}), or -1. */
    private int narrowPlayer = -1;
    /** The current narrowed search's own node and time limits; it gives up past them. */
    private long attemptNodes = Long.MAX_VALUE;
    private long attemptDeadline = Long.MAX_VALUE;
    /** Whether the current narrowed search left out any option (set from the game threads). */
    private final java.util.concurrent.atomic.AtomicBoolean narrowCut = new java.util.concurrent.atomic.AtomicBoolean();
    /** The player narrowed in the search that found the line rootSearch returned (for replaying it), or -1. */
    private int lineNarrow = -1;
    private boolean firstPassOfLimit;
    /** Whether the transposition table holds values from a narrowed search. */
    private boolean tableNarrowed;
    private long deadline;

    // Read by other threads through progress(); only the solving thread writes them.
    private volatile long nodes;
    private volatile long startNanos;
    private volatile int turnLimit;
    private volatile String pass = "starting";
    private volatile boolean cancelled;
    /**
     * The decisions on the current search path, for {@link #progress()}: each is the branch, the
     * option being searched and how many have been tried. Guarded by itself; a lock per child
     * searched is cheap next to replaying the game.
     */
    private final List<Frame> line = new ArrayList<>();
    /** The searched tree, when {@link #recordTree()} asked for it; null otherwise. */
    private SearchTree tree;
    /** The tree node of the position the last {@link #search} call returned from, for its parent to attach. */
    private SearchTree.Node lastNode;

    private static final class Frame {
        final Branch branch;
        int choice;
        int tried;

        Frame(Branch branch) {
            this.branch = branch;
        }
    }

    public Solver(DeckList a, DeckList b, int startingPlayer, Options options) {
        this.runner = new GameRunner(a, b, startingPlayer, options.exhaustive());
        this.startingPlayer = startingPlayer;
        this.options = options;
    }

    /** Live statistics of this search; safe to call from any thread while {@link #solve()} runs. */
    public Progress progress() {
        long start = startNanos;
        long elapsed = start == 0 ? 0 : System.nanoTime() - start;
        long n = nodes;
        double used = Math.max((double) n / options.nodeBudget(), (double) elapsed / options.timeBudget().toNanos());
        List<Decision> decisions = new ArrayList<>();
        int depth;
        synchronized (line) {
            depth = line.size();
            for (Frame f : line.subList(0, Math.min(depth, MAX_LINE))) {
                Branch b = f.branch;
                decisions.add(new Decision(b.player(), b.kind(), Turns.cycle(b.turn()), b.labels().get(f.choice), f.tried,
                        b.labels().size()));
            }
        }
        return new Progress(n, Duration.ofNanos(elapsed), turnLimit, pass, Math.min(1, used), decisions, depth);
    }

    /** Puts a decision on the reported line; {@link #exploring} then names each option searched. */
    private Frame enter(Branch branch) {
        Frame f = new Frame(branch);
        synchronized (line) {
            line.add(f);
        }
        return f;
    }

    private void exploring(Frame f, int choice) {
        synchronized (line) {
            f.choice = choice;
            f.tried++;
        }
    }

    private void leave() {
        synchronized (line) {
            line.remove(line.size() - 1);
        }
    }

    /**
     * Asks a running (or not yet started) {@link #solve()} to stop at its next search node, as if
     * its budget had run out. Safe to call from any thread.
     */
    public void cancel() {
        cancelled = true;
    }

    public boolean cancelled() {
        return cancelled;
    }

    /** Makes {@link #solve()} record the tree it searches; call before solving. */
    public void recordTree() {
        tree = new SearchTree();
    }

    /**
     * The tree the last finished turn limit searched, if {@link #recordTree()} was called and one
     * finished; null otherwise.
     */
    public SearchTree tree() {
        return tree == null || tree.isEmpty() ? null : tree;
    }

    public GameResult solve() {
        long start = System.nanoTime();
        startNanos = start;
        deadline = start + options.timeBudget().toNanos();
        Line best = null;
        int bestLimit = 0;
        int bestNarrow = -1;
        boolean complete = false;
        int cap = options.maxTurns() * EXTEND_FACTOR;
        for (int limit = Math.min(options.firstTurnLimit(), options.maxTurns()); ; limit = nextLimit(limit, cap)) {
            Line line;
            try {
                line = rootSearch(limit);
            } catch (BudgetExceeded e) {
                break;
            }
            boolean decisive = Math.abs(line.value()) > 1 - 1e-9;
            // Past maxTurns the search is only looking for a slow win: a result that still isn't
            // one stays as it was at maxTurns, so a draw is shown at the limit that was asked for.
            if (limit <= options.maxTurns() || decisive) {
                best = line;
                bestLimit = limit;
                bestNarrow = lineNarrow;
                if (tree != null) {
                    tree.keep(limit);
                }
            }
            // A forced win/loss within a limit stays forced with more turns, and so does any result
            // of a search that never ran into the limit. Otherwise go deeper: up to maxTurns to have
            // a result at all, and beyond it, up to the cap, in case a player wins slowly. If the
            // budget runs out on the way there, the draw at maxTurns is the result, but unproven.
            complete = decisive || !limitHit || limit >= cap;
            if (complete) {
                break;
            }
        }
        Duration elapsed = Duration.ofNanos(System.nanoTime() - start);
        GameResult.Bound bound = complete || sawChanceEver ? GameResult.Bound.UNKNOWN
                : bCantWin && !aCantWin ? GameResult.Bound.A_CANT_LOSE
                : aCantWin && !bCantWin ? GameResult.Bound.B_CANT_LOSE
                : GameResult.Bound.UNKNOWN;
        if (best == null || (cancelled && !complete)) {
            String why = cancelled ? "(search stopped)" : "(search budget exhausted before any result)";
            return new GameResult(0, 1, 0, 0, false, List.of(why), nodes, elapsed, bound);
        }
        pass = "replaying the optimal line";
        attempt(bestNarrow, Long.MAX_VALUE, Long.MAX_VALUE); // replay it as it was searched
        return new GameResult(best.pWin(), best.pDraw(), best.pLoss(), bestLimit, complete,
                principalVariation(best.pv(), bestLimit), nodes, elapsed, bound);
    }

    /**
     * Without randomness, two null-window passes instead of one full-window search: "can A force a
     * win?" then "can B force a win?". Most 3CB games are draws, and proving a draw with a full
     * window makes the drawing side keep looking for a win at every node; with a null window one
     * drawing reply per node is enough. Games with random events need exact expectations, so they
     * get a full-window search.
     */
    private Line rootSearch(int limit) {
        table.clear();
        tableNarrowed = false;
        path.clear();
        sawChance = false;
        limitHit = false;
        firstPassOfLimit = true;
        turnLimit = limit;
        // A refuted question that never ran into the limit proves that side can't force a win at
        // all (at the cap, the limit counts as final, as for `complete`).
        boolean finalLimit = limit >= options.maxTurns() * EXTEND_FACTOR;
        Answer a = ask(0, limit);
        sawChanceEver |= sawChance;
        if (!sawChance) {
            if (a.win()) {
                lineNarrow = a.narrowed();
                return a.line();
            }
            aCantWin |= !a.hit() || finalLimit;
            Answer b = ask(1, limit);
            sawChanceEver |= sawChance;
            limitHit = a.hit() || b.hit(); // `complete` needs both answers clear of the limit
            if (b.win()) {
                lineNarrow = b.narrowed();
                return b.line();
            }
            if (!sawChance) {
                bCantWin |= !b.hit() || finalLimit;
            }
            // Neither side can force a win. Show the line where B holds A to a draw.
            lineNarrow = a.narrowed();
            return new Line(0, 0, 1, 0, a.line().pv());
        }
        lineNarrow = -1;
        attempt(-1, Long.MAX_VALUE, Long.MAX_VALUE);
        table.clear();
        return rootPass("expected result (random events)", limit, -1, 1);
    }

    /**
     * The answer to "can X force a win?" at this turn limit (x = 0 for A, 1 for B).
     *
     * @param line     the searched line (a win for X, or the line where the other side holds)
     * @param hit      whether the search that settled it had a game end at the turn limit
     * @param narrowed the player whose options were narrowed in that search, or -1
     */
    private record Answer(Line line, boolean win, boolean hit, int narrowed, boolean exact) {
    }

    /**
     * Narrowed searches first (see {@link Options#narrowing}): with the opponent narrowed, X not
     * winning is proof that X can't; with X narrowed, X winning is proof that X can. Each may use
     * {@link #NARROW_SHARE} of the remaining budget. If neither settles it, the full search does.
     */
    private Answer ask(int x, int limit) {
        String question = x == 0 ? "can A force a win?" : "can B force a win?";
        double alpha = x == 0 ? 0 : -1, beta = x == 0 ? 1 : 0;
        if (options.narrowing() && !options.exhaustive()) {
            Answer hold = narrowed(question + " (" + side(1 - x) + " narrowed)", 1 - x, x, limit, alpha, beta);
            if (sawChance) {
                return hold; // random events: expectimax decides, unnarrowed
            }
            if (hold != null && (!hold.win() || hold.exact())) {
                return hold;
            }
            Answer force = narrowed(question + " (" + side(x) + " narrowed)", x, x, limit, alpha, beta);
            if (sawChance) {
                return force;
            }
            if (force != null && (force.win() || force.exact())) {
                return force;
            }
        }
        attempt(-1, Long.MAX_VALUE, Long.MAX_VALUE);
        if (tableNarrowed) {
            table.clear(); // a narrowed search's values don't hold here (a full pass's do, for the next one)
            tableNarrowed = false;
        }
        limitHit = false;
        Line line = rootPass(question, limit, alpha, beta);
        return new Answer(line, wins(x, line), limitHit, -1, true);
    }

    /** One narrowed search, or null if it used up its share of the budget first. */
    private Answer narrowed(String name, int narrow, int x, int limit, double alpha, double beta) {
        long now = System.nanoTime();
        long nodeShare = (long) ((options.nodeBudget() - nodes) * NARROW_SHARE);
        long timeShare = (long) ((deadline - now) * NARROW_SHARE);
        attempt(narrow, nodes + Math.max(1, nodeShare), now + Math.max(1, timeShare));
        table.clear(); // values found with one side narrowed only hold for that search
        tableNarrowed = true;
        limitHit = false;
        narrowCut.set(false);
        try {
            Line line = rootPass(name, limit, alpha, beta);
            // if no option was left out, this was the full search: its answer holds either way
            return new Answer(line, wins(x, line), limitHit, narrow, !narrowCut.get());
        } catch (AttemptExceeded e) {
            return null;
        } finally {
            attempt(-1, Long.MAX_VALUE, Long.MAX_VALUE);
        }
    }

    private static boolean wins(int x, Line line) {
        return x == 0 ? line.value() >= 1 : line.value() <= -1;
    }

    private static String side(int player) {
        return player == 0 ? "A" : "B";
    }

    /** Sets the narrowed player for the searches that follow, and their own node and time limit. */
    private void attempt(int narrow, long nodeCap, long deadlineNanos) {
        narrowPlayer = narrow;
        attemptNodes = nodeCap;
        attemptDeadline = deadlineNanos;
    }

    /** A tape for replaying {@code steps} in the current search (narrowed like it). */
    private DecisionTape tape(Step[] steps, int limit) {
        DecisionTape t = new DecisionTape(steps, limit);
        return narrowPlayer >= 0 ? t.narrow(narrowPlayer, NARROW_WIDTH, narrowCut) : t;
    }

    /** One search from the start of the game, recorded as a pass of the tree if there is one. */
    private Line rootPass(String name, int limit, double alpha, double beta) {
        pass = name;
        if (tree != null) {
            tree.startPass(firstPassOfLimit);
        }
        firstPassOfLimit = false;
        Line result = search(new Step[0], limit, alpha, beta, null);
        if (tree != null) {
            tree.endPass(name, lastNode);
        }
        return result;
    }

    /**
     * @param known the node's outcome if the caller already has it (the parent's paused game,
     *              resumed); null to replay the game up to the node
     */
    private Line search(Step[] steps, int limit, double alpha, double beta, Outcome known) {
        Outcome outcome = known;
        try {
            long n = nodes + 1;
            nodes = n;
            long now = System.nanoTime();
            if (n > options.nodeBudget() || now > deadline || cancelled) {
                throw new BudgetExceeded();
            }
            if (n > attemptNodes || now > attemptDeadline) {
                throw new AttemptExceeded();
            }
            if (outcome == null) {
                outcome = runner.start(tape(steps, limit));
            }
            lastNode = null;
            if (outcome.isTerminal()) {
                if (atTurnLimit(outcome, limit)) {
                    limitHit = true;
                }
                if (tree != null) {
                    lastNode = tree.end(ending(outcome, limit));
                }
                return Line.of(outcome.value());
            }
            Branch branch = outcome.branch();
            if (branch.player() == DecisionTape.CHANCE) {
                sawChance = true;
                return chance(outcome, steps, limit);
            }
            String position = branch.position();
            if (position != null && !path.add(position)) {
                // The same position recurs on this line: the players can loop forever, which is a draw.
                // (Standard first-repetition rule; a player who can force a win can do so without looping.)
                if (tree != null) {
                    lastNode = tree.end("draw (position repeats)");
                }
                return Line.of(0);
            }
            try {
                return expand(outcome, steps, limit, alpha, beta);
            } finally {
                if (position != null) {
                    path.remove(position);
                }
            }
        } finally {
            if (outcome != null && outcome.continuation() != null) {
                outcome.continuation().abandon();
            }
        }
    }

    /**
     * The outcome of choosing {@code choice} at {@code node}: continues the node's paused game if
     * it hasn't been used yet, which saves replaying it from turn 1. Null if it has (the child
     * then replays).
     */
    private static Outcome resume(Outcome node, int choice) {
        Continuation game = node.continuation();
        return game != null && game.isLive() ? game.resume(choice) : null;
    }

    /** Random event: every outcome equally likely, each searched exactly. */
    private Line chance(Outcome node, Step[] steps, int limit) {
        Branch branch = node.branch();
        int n = branch.labels().size();
        double value = 0, pWin = 0, pDraw = 0, pLoss = 0;
        int[] pv = null;
        SearchTree.Node recorded = tree == null ? null : tree.decision(branch);
        Frame frame = enter(branch);
        try {
            for (int i = 0; i < n; i++) {
                exploring(frame, i);
                Line child = search(append(steps, branch.step(i)), limit, -1, 1, resume(node, i));
                record(recorded, i, child.value(), -1, 1);
                value += child.value() / n;
                pWin += child.pWin() / n;
                pDraw += child.pDraw() / n;
                pLoss += child.pLoss() / n;
                if (pv == null) {
                    pv = prepend(i, child.pv()); // the shown line follows the first outcome
                }
            }
        } finally {
            leave();
        }
        lastNode = recorded;
        return new Line(value, pWin, pDraw, pLoss, pv);
    }

    private Line expand(Outcome node, Step[] steps, int limit, double alpha, double beta) {
        Branch branch = node.branch();
        String key = key(branch);
        double origAlpha = alpha;
        double origBeta = beta;
        int firstTry = -1;
        if (key != null) {
            Entry e = table.get(key);
            if (e != null) {
                double v = e.line().value();
                if (e.bound() == Bound.EXACT
                        || (e.bound() == Bound.LOWER && v >= beta)
                        || (e.bound() == Bound.UPPER && v <= alpha)) {
                    if (tree != null) {
                        lastNode = tree.seen();
                    }
                    return e.line().withPv(new int[] {e.best()});
                }
                firstTry = e.best();
            } else {
                firstTry = bestMoves.getOrDefault(key, -1);
            }
        }

        boolean maximizing = branch.player() == 0;
        Line best = null;
        int bestIndex = -1;
        SearchTree.Node recorded = tree == null ? null : tree.decision(branch);
        Frame frame = enter(branch);
        try {
            for (int i : moveOrder(branch.labels().size(), firstTry)) {
                exploring(frame, i);
                Line child = search(append(steps, branch.step(i)), limit, alpha, beta, resume(node, i));
                double v = child.value();
                record(recorded, i, v, alpha, beta);
                if (best == null || (maximizing ? v > best.value() : v < best.value())) {
                    best = child.withPv(prepend(i, child.pv()));
                    bestIndex = i;
                }
                if (maximizing) {
                    alpha = Math.max(alpha, v);
                } else {
                    beta = Math.min(beta, v);
                }
                if (alpha >= beta) {
                    break;
                }
            }
        } finally {
            leave();
        }
        if (key != null) {
            double v = best.value();
            Bound bound = v <= origAlpha ? Bound.UPPER : v >= origBeta ? Bound.LOWER : Bound.EXACT;
            table.put(key, new Entry(best, bound, bestIndex));
            bestMoves.put(key, bestIndex);
        }
        if (recorded != null) {
            recorded.best = bestIndex;
        }
        lastNode = recorded;
        return best;
    }

    /**
     * Adds option {@code i}, just searched with window (alpha, beta), to a recorded decision. Values
     * outside the window are bounds, except a win or loss: those are as good or bad as it gets.
     */
    private void record(SearchTree.Node decision, int i, double v, double alpha, double beta) {
        if (decision == null) {
            return;
        }
        boolean decisive = Math.abs(v) > 1 - 1e-9;
        char bound = decisive ? '=' : v <= alpha ? '<' : v >= beta ? '>' : '=';
        decision.children.add(new SearchTree.Child(i, v, bound, lastNode));
    }

    /**
     * The order to try options in: the remembered best move, then the rest in the controller's
     * order (actions before passing, bigger attacks first). Killer moves (the option that last
     * caused a cutoff at the same depth) were tried too, and searched 2-3x more nodes: decision
     * depths don't line up across lines, so they're poor guesses here.
     */
    private static int[] moveOrder(int n, int best) {
        int[] order = new int[n];
        int k = 0;
        if (best >= 0 && best < n) {
            order[k++] = best;
        }
        for (int i = 0; i < n; i++) {
            if (i != best) {
                order[k++] = i;
            }
        }
        return order;
    }

    /** Replays the best line, extending it through transposition-table moves, and returns its trace. */
    private List<String> principalVariation(int[] pv, int limit) {
        int[] choices = pv;
        Outcome outcome;
        Set<String> seen = new HashSet<>();
        while (true) {
            DecisionTape t = new DecisionTape(choices, new int[choices.length], limit);
            outcome = runner.run(narrowPlayer >= 0 ? t.narrow(narrowPlayer, NARROW_WIDTH, null) : t);
            if (outcome.isTerminal() || key(outcome.branch()) == null) {
                break;
            }
            if (!seen.add(outcome.branch().position())) {
                List<String> trace = new ArrayList<>(outcome.trace());
                trace.add("=> draw (position repeats)");
                return trace;
            }
            Entry e = table.get(key(outcome.branch()));
            if (e == null || e.best() < 0) {
                break;
            }
            choices = append(choices, e.best());
        }
        List<String> trace = new ArrayList<>(outcome.trace());
        if (outcome.isTerminal()) {
            trace.add("=> " + ending(outcome, limit));
        } else {
            trace.add("...");
        }
        return trace;
    }

    /** How a finished game ended, e.g. "A wins on turn 4 (B's turn)", counting turn cycles. */
    private String ending(Outcome outcome, int limit) {
        String when = " on turn " + Turns.cycle(outcome.turn())
                + " (" + (Turns.activePlayer(outcome.turn(), startingPlayer) == 0 ? "A" : "B") + "'s turn)";
        return switch (outcome.result()) {
            case A_WINS -> "A wins" + when;
            case B_WINS -> "B wins" + when;
            case DRAW -> atTurnLimit(outcome, limit) ? "draw (turn limit " + limit / 2 + ")" : "draw (position repeats)";
        };
    }

    /** Whether a finished game is a draw only because it was still going at the turn limit. */
    private static boolean atTurnLimit(Outcome outcome, int limit) {
        return outcome.result() == GameRunner.Result.DRAW && outcome.turn() > limit;
    }

    /**
     * The turn limit to search after {@code limit}: doubling, stopping at maxTurns and at the cap on
     * the way (or, with jumpToLimit, maxTurns right after the first search).
     */
    private int nextLimit(int limit, int cap) {
        if (options.jumpToLimit() && limit < options.maxTurns()) {
            return options.maxTurns();
        }
        return Math.min(limit * 2, limit < options.maxTurns() ? options.maxTurns() : cap);
    }

    private String key(Branch branch) {
        return options.exactTurns() ? branch.stateKey() : branch.position();
    }

    private static Step[] append(Step[] a, Step x) {
        Step[] r = Arrays.copyOf(a, a.length + 1);
        r[a.length] = x;
        return r;
    }

    private static int[] append(int[] a, int x) {
        int[] r = Arrays.copyOf(a, a.length + 1);
        r[a.length] = x;
        return r;
    }

    private static int[] prepend(int x, int[] a) {
        int[] r = new int[a.length + 1];
        r[0] = x;
        System.arraycopy(a, 0, r, 1, a.length);
        return r;
    }
}
