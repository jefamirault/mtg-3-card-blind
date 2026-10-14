package tcb;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;

import tcb.deck.DeckList;
import tcb.engine.DecisionTape;
import tcb.engine.GameRunner;
import tcb.engine.GameRunner.Outcome;

/** Replaying the same decisions must always reproduce the same game: the solver depends on it. */
class DeterminismTest {
    private static final DeckList[] DECKS = {
            DeckList.of("guide", "Mountain", "Mountain", "Goblin Guide"),
            DeckList.of("ape", "Mountain", "Forest", "Kird Ape"),
            DeckList.of("specter", "Swamp", "Dark Ritual", "Hypnotic Specter"),
            DeckList.of("swords", "Plains", "Plains", "Swords to Plowshares"),
            DeckList.of("counter", "Island", "Island", "Counterspell"),
    };

    @Test
    void randomWalksReplayIdentically() throws Exception {
        // Concurrent, like a tournament: Forge has global state that must not leak between threads.
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<?>> jobs = new ArrayList<>();
            for (int a = 0; a < DECKS.length; a++) {
                for (int b = 0; b < DECKS.length; b++) {
                    if (a == b) continue;
                    final int ia = a, ib = b;
                    jobs.add(pool.submit(() -> walk(DECKS[ia], DECKS[ib], new Random(ia * 31L + ib))));
                }
            }
            for (Future<?> job : jobs) {
                job.get();
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void pausedGamesMatchReplays() {
        // Continuing a paused game must reach exactly the state a fresh replay of the same choices reaches.
        for (int a = 0; a < DECKS.length; a++) {
            for (int b = 0; b < DECKS.length; b++) {
                if (a != b) {
                    continueWalk(DECKS[a], DECKS[b], new Random(a * 31L + b));
                }
            }
        }
    }

    private static void continueWalk(DeckList deckA, DeckList deckB, Random rnd) {
        GameRunner runner = new GameRunner(deckA, deckB, rnd.nextInt(2));
        int[] tape = new int[0];
        Outcome live = runner.start(new DecisionTape(tape, tape, 30));
        while (true) {
            Outcome replay = runner.run(new DecisionTape(tape, new int[tape.length], 30));
            String where = deckA.name() + " vs " + deckB.name() + " tape " + Arrays.toString(tape);
            assertEquals(replay.trace(), live.trace(), where);
            assertEquals(replay.result(), live.result(), where);
            if (replay.isTerminal()) break;
            assertEquals(replay.branch(), live.branch(), where);
            int choice = rnd.nextInt(replay.branch().labels().size());
            tape = Arrays.copyOf(tape, tape.length + 1);
            tape[tape.length - 1] = choice;
            live = live.continuation().resume(choice);
        }
    }

    private static void walk(DeckList deckA, DeckList deckB, Random rnd) {
        GameRunner runner = new GameRunner(deckA, deckB, rnd.nextInt(2));
        for (int walk = 0; walk < 3; walk++) {
            int[] tape = new int[0];
            while (true) {
                Outcome first = runner.run(new DecisionTape(tape, new int[tape.length], 30));
                Outcome again = runner.run(new DecisionTape(tape, new int[tape.length], 30));
                String where = deckA.name() + " vs " + deckB.name() + " tape " + Arrays.toString(tape);
                assertEquals(first.trace(), again.trace(), where);
                assertEquals(first.result(), again.result(), where);
                if (first.isTerminal()) break;
                assertEquals(first.branch(), again.branch(), where);
                tape = Arrays.copyOf(tape, tape.length + 1);
                tape[tape.length - 1] = rnd.nextInt(first.branch().labels().size());
            }
        }
    }
}
