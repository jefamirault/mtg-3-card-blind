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
import tcb.engine.DecisionTape;
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
     * @param maxTurns       turn limit; games still going are draws. With repetition detection it
     *                       is a safety net that should rarely bind.
     * @param firstTurnLimit start of iterative deepening (doubling up to maxTurns); equal to
     *                       maxTurns means a single search
     * @param exactTurns     key the transposition table on (position, turn) instead of position
     *                       alone. Turn-free keys merge "do it now" with "do it a turn later",
     *                       which is much faster, but can misjudge games decided near the limit.
     */
    public record Options(int maxTurns, int firstTurnLimit, long nodeBudget, Duration timeBudget, boolean exactTurns) {
        public static Options defaults() {
            return new Options(50, 50, 2_000_000, Duration.ofMinutes(10), false);
        }
    }

    /**
     * @param pWin              probability deck A wins under optimal play
     * @param pDraw             probability of a draw
     * @param pLoss             probability deck B wins
     * @param provenTurnLimit   largest turn limit fully searched (0 if not even the first finished)
     * @param complete          true if the result is final: decisive, or searched up to maxTurns
     * @param principalVariation the optimal line found, as readable decisions
     */
    public record GameResult(double pWin, double pDraw, double pLoss, int provenTurnLimit, boolean complete,
                             List<String> principalVariation, long nodes, Duration elapsed) {
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
     * @param turnLimit  turn limit of the current pass (iterative deepening doubles it)
     * @param pass       what the current pass is trying to prove
     * @param budgetUsed fraction of the node or time budget used, whichever is larger (0 to 1)
     */
    public record Progress(long nodes, Duration elapsed, int turnLimit, String pass, double budgetUsed) {
    }

    private final GameRunner runner;
    private final Options options;
    private final Map<String, Entry> table = new HashMap<>();
    /** Positions on the current search path, for repetition detection. */
    private final Set<String> path = new HashSet<>();
    private boolean sawChance;
    private long deadline;

    // Read by other threads through progress(); only the solving thread writes them.
    private volatile long nodes;
    private volatile long startNanos;
    private volatile int turnLimit;
    private volatile String pass = "starting";

    public Solver(DeckList a, DeckList b, int startingPlayer, Options options) {
        this.runner = new GameRunner(a, b, startingPlayer);
        this.options = options;
    }

    /** Live statistics of this search; safe to call from any thread while {@link #solve()} runs. */
    public Progress progress() {
        long start = startNanos;
        long elapsed = start == 0 ? 0 : System.nanoTime() - start;
        long n = nodes;
        double used = Math.max((double) n / options.nodeBudget(), (double) elapsed / options.timeBudget().toNanos());
        return new Progress(n, Duration.ofNanos(elapsed), turnLimit, pass, Math.min(1, used));
    }

    public GameResult solve() {
        long start = System.nanoTime();
        startNanos = start;
        deadline = start + options.timeBudget().toNanos();
        Line best = null;
        int bestLimit = 0;
        boolean complete = false;
        for (int limit = Math.min(options.firstTurnLimit(), options.maxTurns()); ; limit = Math.min(limit * 2, options.maxTurns())) {
            Line line;
            try {
                line = rootSearch(limit);
            } catch (BudgetExceeded e) {
                break;
            }
            best = line;
            bestLimit = limit;
            // A forced win/loss within a limit stays forced with more turns; otherwise go deeper.
            complete = Math.abs(line.value()) > 1 - 1e-9 || limit >= options.maxTurns();
            if (complete) {
                break;
            }
        }
        Duration elapsed = Duration.ofNanos(System.nanoTime() - start);
        if (best == null) {
            return new GameResult(0, 1, 0, 0, false, List.of("(search budget exhausted before any result)"), nodes, elapsed);
        }
        pass = "replaying the optimal line";
        return new GameResult(best.pWin(), best.pDraw(), best.pLoss(), bestLimit, complete,
                principalVariation(best.pv(), bestLimit), nodes, elapsed);
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
        path.clear();
        sawChance = false;
        turnLimit = limit;
        pass = "can A force a win?";
        Line aWins = search(new int[0], new int[0], limit, 0, 1);
        if (!sawChance) {
            if (aWins.value() >= 1) {
                return aWins;
            }
            pass = "can B force a win?";
            Line bWins = search(new int[0], new int[0], limit, -1, 0);
            if (bWins.value() <= -1) {
                return bWins;
            }
            // Neither side can force a win. Show the line where B holds A to a draw.
            return new Line(0, 0, 1, 0, aWins.pv());
        }
        table.clear();
        pass = "expected result (random events)";
        return search(new int[0], new int[0], limit, -1, 1);
    }

    private Line search(int[] choices, int[] checks, int limit, double alpha, double beta) {
        long n = nodes + 1;
        nodes = n;
        if (n > options.nodeBudget() || System.nanoTime() > deadline) {
            throw new BudgetExceeded();
        }
        Outcome outcome = runner.run(new DecisionTape(choices, checks, limit));
        if (outcome.isTerminal()) {
            return Line.of(outcome.value());
        }
        Branch branch = outcome.branch();
        if (branch.player() == DecisionTape.CHANCE) {
            sawChance = true;
            return chance(branch, choices, checks, limit);
        }
        String position = branch.position();
        if (position != null && !path.add(position)) {
            // The same position recurs on this line: the players can loop forever, which is a draw.
            // (Standard first-repetition rule; a player who can force a win can do so without looping.)
            return Line.of(0);
        }
        try {
            return expand(branch, choices, checks, limit, alpha, beta);
        } finally {
            if (position != null) {
                path.remove(position);
            }
        }
    }

    /** Random event: every outcome equally likely, each searched exactly. */
    private Line chance(Branch branch, int[] choices, int[] checks, int limit) {
        int checksum = DecisionTape.checksum(branch.labels());
        int n = branch.labels().size();
        double value = 0, pWin = 0, pDraw = 0, pLoss = 0;
        int[] pv = null;
        for (int i = 0; i < n; i++) {
            Line child = search(append(choices, i), append(checks, checksum), limit, -1, 1);
            value += child.value() / n;
            pWin += child.pWin() / n;
            pDraw += child.pDraw() / n;
            pLoss += child.pLoss() / n;
            if (pv == null) {
                pv = prepend(i, child.pv()); // the shown line follows the first outcome
            }
        }
        return new Line(value, pWin, pDraw, pLoss, pv);
    }

    private Line expand(Branch branch, int[] choices, int[] checks, int limit, double alpha, double beta) {
        String key = key(branch);
        double origAlpha = alpha;
        double origBeta = beta;
        int firstTry = 0;
        if (key != null) {
            Entry e = table.get(key);
            if (e != null) {
                double v = e.line().value();
                if (e.bound() == Bound.EXACT
                        || (e.bound() == Bound.LOWER && v >= beta)
                        || (e.bound() == Bound.UPPER && v <= alpha)) {
                    return e.line().withPv(new int[] {e.best()});
                }
                firstTry = e.best();
            }
        }

        boolean maximizing = branch.player() == 0;
        int checksum = DecisionTape.checksum(branch.labels());
        int n = branch.labels().size();
        Line best = null;
        int bestIndex = -1;
        for (int k = 0; k < n; k++) {
            // try the transposition table's best move first
            int i = k == 0 ? firstTry : (k <= firstTry ? k - 1 : k);
            Line child = search(append(choices, i), append(checks, checksum), limit, alpha, beta);
            double v = child.value();
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
        if (key != null) {
            double v = best.value();
            Bound bound = v <= origAlpha ? Bound.UPPER : v >= origBeta ? Bound.LOWER : Bound.EXACT;
            table.put(key, new Entry(best, bound, bestIndex));
        }
        return best;
    }

    /** Replays the best line, extending it through transposition-table moves, and returns its trace. */
    private List<String> principalVariation(int[] pv, int limit) {
        int[] choices = pv;
        Outcome outcome;
        Set<String> seen = new HashSet<>();
        while (true) {
            outcome = runner.run(new DecisionTape(choices, new int[choices.length], limit));
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
            trace.add("=> " + switch (outcome.result()) {
                case A_WINS -> "A wins on turn " + outcome.turn();
                case B_WINS -> "B wins on turn " + outcome.turn();
                case DRAW -> outcome.turn() > limit ? "draw (turn limit " + limit + ")" : "draw (position repeats)";
            });
        } else {
            trace.add("...");
        }
        return trace;
    }

    private String key(Branch branch) {
        return options.exactTurns() ? branch.stateKey() : branch.position();
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
