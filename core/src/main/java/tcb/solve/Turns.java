package tcb.solve;

/**
 * Turn numbers as players count them. Forge counts each player's turn (1, 2, 3, ... alternating
 * players), and the solver works in those internally: turn limits, transposition keys. Everything
 * shown counts turn cycles instead: turn N is each player's Nth turn, starting at 1.
 */
public final class Turns {
    private Turns() {
    }

    /** The turn cycle a Forge turn falls in. Decisions before the first turn (turn 0) count as turn 1. */
    public static int cycle(int forgeTurn) {
        return Math.max(1, (forgeTurn + 1) / 2);
    }

    /** The Forge turn limit for a limit of {@code cycles} turn cycles: both players' turns of each. */
    public static int forgeTurns(int cycles) {
        return 2 * cycles;
    }

    /** Whose turn a Forge turn is: 0 for deck A, 1 for deck B. Turn 0 counts as the first. */
    public static int activePlayer(int forgeTurn, int startingPlayer) {
        return forgeTurn % 2 == 1 || forgeTurn == 0 ? startingPlayer : 1 - startingPlayer;
    }
}
