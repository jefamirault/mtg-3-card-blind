package tcb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import org.junit.jupiter.api.Test;

import tcb.deck.DeckList;
import tcb.solve.Solver;
import tcb.solve.Solver.GameResult;
import tcb.solve.Solver.Options;
import tcb.solve.Scoring;
import tcb.solve.Tournament;
import tcb.solve.Tournament.Results;
import tcb.solve.Tournament.Standing;

/** Stopping a tournament early, and scoring. */
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

    private static GameResult result(double pWin, double pDraw, double pLoss) {
        return new GameResult(pWin, pDraw, pLoss, 50, true, List.of(), 0, Duration.ZERO);
    }

    private static Tournament.Game game(int a, int b, int start, double pWinA, double pDraw) {
        return new Tournament.Game(a, b, start, result(pWinA, pDraw, 1 - pWinA - pDraw), false, null);
    }

    private static List<String> order(Results r) {
        return r.standings().stream().map(s -> s.deck().name()).toList();
    }

    @Test
    void rescoresTheSameGames() {
        List<Tournament.Game> games = List.of(
                game(0, 1, 0, 1, 0), game(0, 1, 1, 0, 0), // guide vs islands: one win each
                game(0, 2, 0, 0, 1), game(0, 2, 1, 0, 1), // guide vs plains: two draws
                game(1, 2, 0, 0, 0), game(1, 2, 1, 0, 0)); // islands vs plains: plains wins both
        Results three = new Results(FIELD, games, List.of(), List.of(), Scoring.THREE_ONE).scoredBy(Scoring.THREE_ONE);
        // plains: 2 draws, 2 wins = 8; guide: 1 win, 2 draws, 1 loss = 5; islands: 1 win = 3
        assertEquals(List.of("plains", "guide", "islands"), order(three));
        assertEquals(5, three.standings().get(1).points(), 1e-9);
        assertEquals(6, three.pointsAgainst(2, 1), 1e-9);
        assertEquals(2, three.pointsAgainst(0, 2), 1e-9);

        Results two = three.scoredBy(Scoring.TWO_ONE);
        assertEquals(List.of("plains", "guide", "islands"), order(two));
        Standing guide = two.standings().get(1);
        assertEquals(4, guide.points(), 1e-9);
        assertEquals(50, two.percent(guide), 1e-9); // 4 of 8
        assertEquals(75, two.percent(two.standings().get(0)), 1e-9); // plains: 6 of 8
        assertEquals(4, two.pointsAgainst(2, 1), 1e-9);
        assertEquals(2, two.pointsAgainst(0, 2), 1e-9);
        assertEquals(games, two.games());
    }

    @Test
    void aDrawIsHalfAWinUnderTwoOne() {
        // Issue #28: all draws against winning a third and losing the rest. They tie on points
        // under 3-1-0 (more wins breaks the tie); all draws is ahead under 2-1-0.
        List<Tournament.Game> games = List.of(
                game(0, 2, 0, 0, 1), game(0, 2, 1, 0, 1), // guide draws both against plains
                game(1, 2, 0, 1.0 / 3, 0), game(1, 2, 1, 1.0 / 3, 0)); // islands wins a third
        Results three = new Results(FIELD, games, List.of(), List.of(), Scoring.THREE_ONE).scoredBy(Scoring.THREE_ONE);
        assertEquals(List.of("plains", "islands", "guide"), order(three));
        assertEquals(three.standings().get(1).points(), three.standings().get(2).points(), 1e-9);

        Results two = three.scoredBy(Scoring.TWO_ONE);
        assertEquals(List.of("plains", "guide", "islands"), order(two));
        assertEquals(50, two.percent(two.standings().get(1)), 1e-9);
        assertEquals(100.0 / 3, two.percent(two.standings().get(2)), 1e-9);
    }

    @Test
    void parsesScoring() {
        assertEquals(Scoring.TWO_ONE, Scoring.parse("2-1-0"));
        assertEquals(Scoring.THREE_ONE, Scoring.parse(" 3-1-0 "));
        assertThrows(IllegalArgumentException.class, () -> Scoring.parse("1-0.5-0"));
    }
}
