package tcb.engine;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Feeds pre-recorded choices to the solver controllers while a game is replayed. When the tape
 * runs out at a real decision (two or more options) the game is interrupted with
 * {@link Signals.BranchReached} so the search can expand that node.
 */
public final class DecisionTape {
    private final int[] choices;
    private final int[] checksums;
    private final int turnLimit;
    private int pos;
    private final List<String> trace = new ArrayList<>();
    private final Set<String> checkpoints = new HashSet<>();
    private int lastCheckpointTurn = -1;
    /** Set when the game runs on its own thread and pauses at new decisions instead of stopping. */
    private Continuation.Handoff handoff;

    /**
     * @param choices   option index for each successive branching decision
     * @param checksums label checksum recorded when each choice was made (0 = don't verify)
     */
    public DecisionTape(int[] choices, int[] checksums, int turnLimit) {
        this.choices = choices;
        this.checksums = checksums;
        this.turnLimit = turnLimit;
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
     * Picks one of {@code labels}. Single-option decisions are taken without using the tape.
     * Past the end of the tape, either stops the game ({@link Signals.BranchReached}) or, for a
     * game started with {@link GameRunner#start}, pauses until the search picks an option.
     */
    public int decide(int player, String kind, List<String> labels, int turn, Supplier<String> position) {
        if (labels.isEmpty()) {
            throw new IllegalArgumentException("decision without options: " + kind);
        }
        if (labels.size() == 1) {
            return 0;
        }
        int choice;
        if (pos >= choices.length) {
            Signals.Branch branch = new Signals.Branch(player, kind, List.copyOf(labels), position.get(), turn);
            if (handoff == null) {
                throw new Signals.BranchReached(branch);
            }
            handoff.toSearch(new GameRunner.Outcome(null, branch, List.copyOf(trace), turn, new Continuation(handoff)));
            choice = handoff.awaitChoice();
            if (choice >= labels.size()) {
                throw new IllegalArgumentException("choice " + choice + " out of range for " + labels);
            }
        } else {
            choice = choices[pos];
            int expected = checksums[pos];
            if (choice >= labels.size() || (expected != 0 && expected != checksum(labels))) {
                throw new IllegalStateException("Non-deterministic replay at decision " + pos + " (" + kind
                        + "): options are now " + labels);
            }
            pos++;
        }
        String who = player == CHANCE ? "Chance" : player == 0 ? "A" : "B";
        trace.add(who + " " + kind + ": " + labels.get(choice));
        return choice;
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
