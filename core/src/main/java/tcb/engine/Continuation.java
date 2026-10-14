package tcb.engine;

import java.util.concurrent.SynchronousQueue;

/**
 * A live game paused at an unexplored decision. The game runs on its own thread, blocked inside
 * the decision; {@link #resume(int)} feeds it a choice and runs it to the next unexplored decision
 * (or the end), which is much cheaper than replaying the whole game from turn 1.
 *
 * Each continuation can be used once: either resumed or abandoned. The search resumes the first
 * child it explores and replays the others; a continuation that is never resumed must be
 * abandoned so its thread ends.
 */
public final class Continuation {
    private final Handoff handoff;
    private boolean used;

    Continuation(Handoff handoff) {
        this.handoff = handoff;
    }

    /** True until resumed or abandoned. */
    public boolean isLive() {
        return !used;
    }

    /** Continues the paused game with {@code choice} and returns its next outcome. */
    public GameRunner.Outcome resume(int choice) {
        if (used) {
            throw new IllegalStateException("continuation already used");
        }
        used = true;
        handoff.toGame(choice);
        return handoff.fromGame();
    }

    /** Ends the paused game without playing on. Does nothing if already used. */
    public void abandon() {
        if (!used) {
            used = true;
            handoff.toGame(Handoff.ABANDON);
        }
    }

    /** Thrown inside the game thread to unwind an abandoned game. */
    static final class Abandoned extends Error {
        Abandoned() {
            super(null, null, false, false);
        }
    }

    /** The two rendezvous points between the search thread and one game thread. */
    static final class Handoff {
        static final int ABANDON = -1;

        private final SynchronousQueue<Object> outcomes = new SynchronousQueue<>();
        private final SynchronousQueue<Integer> choices = new SynchronousQueue<>();

        /** Game thread: hands an outcome (or a failure) to the search. */
        void toSearch(Object outcomeOrError) {
            try {
                outcomes.put(outcomeOrError);
            } catch (InterruptedException e) {
                throw new Abandoned();
            }
        }

        /** Game thread: waits for the search's choice at a paused decision. */
        int awaitChoice() {
            int choice;
            try {
                choice = choices.take();
            } catch (InterruptedException e) {
                throw new Abandoned();
            }
            if (choice == ABANDON) {
                throw new Abandoned();
            }
            return choice;
        }

        void toGame(int choice) {
            try {
                choices.put(choice);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted while resuming a game", e);
            }
        }

        GameRunner.Outcome fromGame() {
            Object o;
            try {
                o = outcomes.take();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted while waiting for a game", e);
            }
            if (o instanceof RuntimeException e) {
                throw e;
            }
            if (o instanceof Error e) {
                throw e;
            }
            return (GameRunner.Outcome) o;
        }
    }
}
