package tcb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;

import org.junit.jupiter.api.Test;

import tcb.deck.DeckList;
import tcb.solve.Solver;
import tcb.solve.Solver.GameResult;
import tcb.solve.Solver.Options;

/**
 * Narrowed searches (one side's options cut down, see {@link Options#narrowing}) only settle a
 * question when the cut can't matter: a win by the narrowed side, or a hold against the narrowed
 * side. So results with narrowing must be the same as without it.
 */
class NarrowingTest {
    private static final DeckList GUIDE = DeckList.of("guide", "Mountain", "Mountain", "Goblin Guide");
    private static final DeckList SWORDS = DeckList.of("swords", "Plains", "Plains", "Swords to Plowshares");
    private static final DeckList ISLANDS = DeckList.of("islands", "Island", "Island", "Island");
    private static final DeckList MEMNITES = DeckList.of("memnites", "Memnite", "Memnite", "Memnite");
    private static final DeckList TUSKER = DeckList.of("tusker", "Forest", "Forest", "Kalonian Tusker");
    private static final DeckList COUNTER = DeckList.of("counter", "Island", "Island", "Counterspell");

    private static final Options OPTS = new Options(12, 12, 200_000, Duration.ofMinutes(5), false).withNarrowing(true);

    private static void sameResult(DeckList a, DeckList b) {
        for (int start = 0; start < 2; start++) {
            GameResult narrowed = new Solver(a, b, start, OPTS).solve();
            GameResult plain = new Solver(a, b, start, OPTS.withNarrowing(false)).solve();
            System.out.printf("%s vs %s (P%d first): %s in %d nodes; without narrowing %s in %d nodes%n", a.name(), b.name(),
                    start + 1, narrowed.describe(), narrowed.nodes(), plain.describe(), plain.nodes());
            assertTrue(narrowed.complete() && plain.complete(), "both searches should finish");
            assertEquals(plain.pWin(), narrowed.pWin(), 1e-9);
            assertEquals(plain.pDraw(), narrowed.pDraw(), 1e-9);
            assertEquals(plain.pLoss(), narrowed.pLoss(), 1e-9);
        }
    }

    @Test
    void decisiveGames() {
        sameResult(GUIDE, ISLANDS); // A wins either way
        sameResult(MEMNITES, SWORDS); // the Memnites win
    }

    @Test
    void draws() {
        sameResult(GUIDE, SWORDS);
        sameResult(TUSKER, COUNTER); // on the play the Tusker wins, on the draw it's countered
    }
}
