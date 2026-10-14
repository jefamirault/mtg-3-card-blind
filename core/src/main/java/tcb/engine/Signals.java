package tcb.engine;

import java.util.List;

/**
 * Control-flow signals thrown out of Forge's game loop. They extend {@link Error} so Forge's
 * many {@code catch (Exception e)} blocks don't swallow them, and skip stack traces for speed.
 */
public final class Signals {
    private Signals() {
    }

    /** A decision point not covered by the replay tape: the search must branch here. */
    public static final class BranchReached extends Error {
        public final Branch branch;

        BranchReached(Branch branch) {
            super(null, null, false, false);
            this.branch = branch;
        }
    }

    /** A position recurred during the game, so play can loop forever; scored as a draw. */
    public static final class PositionRepeated extends Error {
        PositionRepeated() {
            super(null, null, false, false);
        }
    }

    /** The game went past the turn limit; scored as a draw. */
    public static final class TurnLimitReached extends Error {
        TurnLimitReached() {
            super(null, null, false, false);
        }
    }

    /** A decision with more than one option.
     *
     * @param player   index of the deciding player (0 = deck A, 1 = deck B)
     * @param kind     decision type, e.g. "priority", "attack"
     * @param labels   human-readable options, in the order the search should try them
     * @param position canonical game position excluding the turn number, or null when the state
     *                 can't be serialised faithfully (e.g. non-empty stack, mid-combat)
     * @param turn     current turn number
     */
    public record Branch(int player, String kind, List<String> labels, String position, int turn) {
        /** Transposition-table key: the position plus the turn (values depend on turns remaining). */
        public String stateKey() {
            return position == null ? null : turn + "|" + position;
        }
    }
}
