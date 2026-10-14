package tcb.engine;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

import forge.game.phase.PhaseType;

/**
 * Feeds pre-recorded choices to the solver controllers while a game is replayed. When the tape
 * runs out at a real decision (two or more options) the game is interrupted with
 * {@link Signals.BranchReached} so the search can expand that node.
 */
public final class DecisionTape {
    /**
     * One recorded choice.
     *
     * @param choice   option index
     * @param checksum label checksum when the choice was made (0 = don't verify)
     * @param ordinal  {@link Signals.Branch#ordinal()} of the decision, or -1 if unknown
     * @param pass     the choice was "Pass" at a priority decision
     */
    public record Step(int choice, int checksum, int ordinal, boolean pass) {
    }

    private static final String PASS = "Pass";

    private final Step[] steps;
    private final int turnLimit;
    private int pos;
    /** Decisions asked for so far, including those with a single option. */
    private int calls;
    private final List<String> trace = new ArrayList<>();
    private final Set<String> checkpoints = new HashSet<>();
    private int lastCheckpointTurn = -1;
    private int priorityTurn = -1;
    private PhaseType priorityPhase;
    private PhaseType previousPriorityPhase;
    /** Set when the game runs on its own thread and pauses at new decisions instead of stopping. */
    private Continuation.Handoff handoff;

    /** @param steps the choice at each successive branching decision */
    public DecisionTape(Step[] steps, int turnLimit) {
        this.steps = steps;
        this.turnLimit = turnLimit;
    }

    /**
     * @param choices   option index for each successive branching decision
     * @param checksums label checksum recorded when each choice was made (0 = don't verify)
     */
    public DecisionTape(int[] choices, int[] checksums, int turnLimit) {
        this(new Step[choices.length], turnLimit);
        for (int i = 0; i < choices.length; i++) {
            steps[i] = new Step(choices[i], checksums[i], -1, false);
        }
    }

    void pauseAtNewDecisions(Continuation.Handoff handoff) {
        this.handoff = handoff;
    }

    public int turnLimit() {
        return turnLimit;
    }

    /** Throws {@link Signals.TurnLimitReached} once the game passes the turn limit. */
    public void checkTurn(int turn) {
        if (turn > turnLimit) {
            throw new Signals.TurnLimitReached();
        }
    }

    /** {@link Signals.Branch#player()} value for chance nodes. */
    public static final int CHANCE = -1;

    /** A random event with {@code outcomes} equally likely results. */
    int chance(int outcomes, int turn) {
        List<String> labels = new ArrayList<>(outcomes);
        for (int i = 1; i <= outcomes; i++) {
            labels.add("random outcome " + i + " of " + outcomes);
        }
        return decide(CHANCE, "chance", labels, turn, () -> null);
    }

    /**
     * Records a priority decision in {@code phase} and returns the phase of the previous window
     * this turn: the phase of the last earlier priority decision in a different phase, or null.
     */
    PhaseType notePriority(int turn, PhaseType phase) {
        if (turn != priorityTurn) {
            priorityTurn = turn;
            priorityPhase = null;
            previousPriorityPhase = null;
        }
        if (phase != priorityPhase) {
            previousPriorityPhase = priorityPhase;
            priorityPhase = phase;
        }
        return previousPriorityPhase;
    }

    /** True the first time it's called in a given turn. */
    boolean checkpointDue(int turn) {
        if (turn == lastCheckpointTurn) {
            return false;
        }
        lastCheckpointTurn = turn;
        return true;
    }

    /**
     * Records a once-per-turn position. If it was seen before in this game, play is looping
     * (positions are complete and the game is deterministic), so it's a draw.
     */
    void checkpoint(String position) {
        if (!checkpoints.add(position)) {
            throw new Signals.PositionRepeated();
        }
    }

    /**
     * Replay shortcut for a priority decision: true if the tape shows that the player passes here,
     * so the caller can pass without listing every option. Counts as the decision (don't also call
     * {@link #decide}). Needs steps with known ordinals.
     */
    boolean replaysPass(int player) {
        if (pos >= steps.length || steps[pos].ordinal() < 0) {
            return false;
        }
        Step next = steps[pos];
        if (next.ordinal() > calls) {
            // Not a branching decision when the tape was recorded: passing was the only option.
            calls++;
            return true;
        }
        if (next.ordinal() == calls && next.pass()) {
            calls++;
            pos++;
            trace.add(who(player) + " priority: " + PASS);
            return true;
        }
        return false;
    }

    /**
     * Picks one of {@code labels}. Single-option decisions are taken without using the tape.
     * Past the end of the tape, either stops the game ({@link Signals.BranchReached}) or, for a
     * game started with {@link GameRunner#start}, pauses until the search picks an option.
     */
    public int decide(int player, String kind, List<String> labels, int turn, Supplier<String> position) {
        if (labels.isEmpty()) {
            throw new IllegalArgumentException("decision without options: " + kind);
        }
        int ordinal = calls++;
        if (labels.size() == 1) {
            return 0;
        }
        int choice;
        if (pos >= steps.length) {
            Signals.Branch branch = new Signals.Branch(player, kind, List.copyOf(labels), position.get(), turn, ordinal);
            if (handoff == null) {
                throw new Signals.BranchReached(branch);
            }
            handoff.toSearch(new GameRunner.Outcome(null, branch, List.copyOf(trace), turn, new Continuation(handoff)));
            choice = handoff.awaitChoice();
            if (choice >= labels.size()) {
                throw new IllegalArgumentException("choice " + choice + " out of range for " + labels);
            }
        } else {
            Step step = steps[pos];
            choice = step.choice();
            int expected = step.checksum();
            if (choice >= labels.size() || (expected != 0 && expected != checksum(labels))
                    || (step.ordinal() >= 0 && step.ordinal() != ordinal)) {
                throw new IllegalStateException("Non-deterministic replay at decision " + pos + " (" + kind
                        + "): options are now " + labels);
            }
            pos++;
        }
        trace.add(who(player) + " " + kind + ": " + labels.get(choice));
        return choice;
    }

    private static String who(int player) {
        return player == CHANCE ? "Chance" : player == 0 ? "A" : "B";
    }

    public static int checksum(List<String> labels) {
        int h = String.join("\u0000", labels).hashCode();
        return h == 0 ? 1 : h;
    }

    /** Choices actually taken so far, human-readable. */
    public List<String> trace() {
        return trace;
    }
}
