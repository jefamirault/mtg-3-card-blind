package tcb.engine;

/**
 * A replay didn't reproduce the game it was recorded from: a decision's options changed. The
 * solver can't continue that search; see {@link tcb.solve.Tournament} for the retry.
 */
public final class NondeterministicReplayException extends IllegalStateException {
    public NondeterministicReplayException(String message) {
        super(message);
    }

    /** True if {@code e} or one of its causes is a non-deterministic replay. */
    public static boolean causedBy(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof NondeterministicReplayException) {
                return true;
            }
        }
        return false;
    }
}
