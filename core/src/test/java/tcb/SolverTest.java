package tcb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;

import org.junit.jupiter.api.Test;

import tcb.deck.DeckList;
import tcb.engine.DecisionTape;
import tcb.engine.GameRunner;
import tcb.solve.Solver;
import tcb.solve.Solver.GameResult;
import tcb.solve.Solver.Options;

/** Known 3 Card Blind matchups. Values are from deck A's point of view. */
class SolverTest {
    private static final DeckList GUIDE = DeckList.of("guide", "Mountain", "Mountain", "Goblin Guide");
    private static final DeckList ISLANDS = DeckList.of("islands", "Island", "Island", "Island");
    private static final DeckList PLAINS = DeckList.of("plains", "Plains", "Plains", "Plains");
    private static final DeckList SWORDS = DeckList.of("swords", "Plains", "Plains", "Swords to Plowshares");

    private static final Options OPTS = new Options(30, 30, 200_000, Duration.ofMinutes(5), false);

    private static GameResult solve(DeckList a, DeckList b, int startingPlayer) {
        GameResult r = new Solver(a, b, startingPlayer, OPTS).solve();
        System.out.printf("%s vs %s (P%d first): %s, limit %d, complete=%s, %d nodes, %s%n",
                a.name(), b.name(), startingPlayer + 1, r.describe(), r.provenTurnLimit(), r.complete(), r.nodes(), r.elapsed());
        r.principalVariation().forEach(line -> System.out.println("    " + line));
        return r;
    }

    @Test
    void emptyLibraryDoesNotLose() {
        // No lands, so nobody can ever act; both players draw from an empty library every turn.
        DeckList bears = DeckList.of("bears", "Grizzly Bears", "Grizzly Bears", "Grizzly Bears");
        DeckList recall = DeckList.of("recall", "Ancestral Recall", "Grizzly Bears", "Grizzly Bears");
        GameRunner.Outcome o = new GameRunner(bears, recall, 0).run(new DecisionTape(new int[0], new int[0], 20));
        assertTrue(o.isTerminal(), "a game where nothing can be cast should need no decisions: " + o.branch());
        assertEquals(GameRunner.Result.DRAW, o.result());
        // B draws from its empty library on turn 2; under normal rules that loses. Here the game
        // carries on until the position repeats (a draw).
        assertTrue(o.turn() >= 3, "game should get past B's first draw step, not end by decking (ended turn " + o.turn() + ")");
    }

    @Test
    void landsOnlyIsADraw() {
        assertEquals(0, solve(PLAINS, ISLANDS, 0).value(), 1e-9);
    }

    @Test
    void goblinGuideBeatsIslandsOnThePlay() {
        GameResult r = solve(GUIDE, ISLANDS, 0);
        assertEquals(1, r.value(), 1e-9);
        assertTrue(r.complete());
    }

    @Test
    void goblinGuideBeatsIslandsOnTheDraw() {
        assertEquals(1, solve(GUIDE, ISLANDS, 1).value(), 1e-9);
    }

    @Test
    void counterspellNeedsToBeOnThePlay() {
        DeckList tusker = DeckList.of("tusker", "Forest", "Forest", "Kalonian Tusker");
        DeckList counter = DeckList.of("counterspell", "Island", "Island", "Counterspell");
        // On the play, the GG creature lands before UU is up.
        assertEquals(1, solve(tusker, counter, 0).value(), 1e-9);
        // On the draw, the counterspell player holds up UU and counters it (a spell on the stack).
        GameResult r = solve(tusker, counter, 1);
        assertEquals(0, r.value(), 1e-9);
        assertTrue(r.principalVariation().stream().anyMatch(l -> l.contains("Cast Counterspell -> spell Kalonian Tusker")),
                "optimal line should counter the Tusker: " + r.principalVariation());
    }

    @Test
    void coinFlipIsAChanceNode() {
        // Sacrificing the Bottle flips a coin: win -> 5/5 flying Djinn (wins the game),
        // lose -> 5 damage to its controller (nothing else happens: a draw). Flipping beats a
        // certain draw, so optimal play is 50% win / 50% draw.
        DeckList bottle = DeckList.of("bottle", "Ancient Tomb", "Sol Ring", "Bottle of Suleiman");
        GameResult r = solve(bottle, ISLANDS, 0);
        assertEquals(0.5, r.pWin(), 1e-9);
        assertEquals(0.5, r.pDraw(), 1e-9);
        assertEquals(0.0, r.pLoss(), 1e-9);
    }

    @Test
    void swordsStopsGoblinGuide() {
        assertEquals(0, solve(GUIDE, SWORDS, 0).value(), 1e-9);
        assertEquals(0, solve(GUIDE, SWORDS, 1).value(), 1e-9);
    }
}
