package tcb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import org.junit.jupiter.api.Test;

import tcb.deck.DeckList;
import tcb.solve.Solver;
import tcb.solve.Solver.GameResult;
import tcb.solve.Solver.Options;
import tcb.solve.Tournament;
import tcb.solve.Tournament.Results;

/** Stopping a tournament early. */
class TournamentTest {
    private static final List<DeckList> FIELD = List.of(
            DeckList.of("guide", "Mountain", "Mountain", "Goblin Guide"),
            DeckList.of("islands", "Island", "Island", "Island"),
            DeckList.of("plains", "Plains", "Plains", "Plains"));

    private static final Options OPTS = new Options(30, 30, 200_000, Duration.ofMinutes(5), false);

    @Test
    void cancelledSolverReportsNoResult() {
        Solver solver = new Solver(FIELD.get(0), FIELD.get(1), 0, OPTS);
        solver.cancel();
        GameResult r = solver.solve();
        assertTrue(solver.cancelled());
        assertFalse(r.complete());
        assertEquals(List.of("(search stopped)"), r.principalVariation());
    }

    @Test
    void stopBeforeStartPlaysNothing() {
        Tournament.Stop stop = new Tournament.Stop();
        stop.request();
        Results r = Tournament.run(FIELD, OPTS, 2, line -> { }, ConcurrentHashMap.newKeySet(), stop, null);
        assertEquals(0, r.games().size());
        assertEquals(6, r.notPlayed().size());
        assertFalse(r.allComplete());
        assertFalse(r.bothPlayed(0, 1));
    }

    @Test
    void stopKeepsFinishedGames() {
        // One thread, so games run in order: stopping after the first leaves the other five unplayed.
        Tournament.Stop stop = new Tournament.Stop();
        Results r = Tournament.run(FIELD, OPTS, 1, line -> stop.request(), ConcurrentHashMap.newKeySet(), stop, null);
        assertEquals(1, r.games().size());
        assertTrue(r.games().get(0).result().complete());
        assertEquals(5, r.notPlayed().size());
        assertFalse(r.bothPlayed(0, 1), "guide vs islands had only one of its two games played");
        assertFalse(r.bothPlayed(1, 2));
        assertEquals(3, r.standings().size());
    }
}
