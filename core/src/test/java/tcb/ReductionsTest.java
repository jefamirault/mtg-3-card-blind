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
 * The branching reductions (merging options that only swap identical cards, skipping priority
 * windows that repeat earlier ones) must not change any result: compare with exhaustive search.
 */
class ReductionsTest {
    private static final DeckList MEMNITES = DeckList.of("memnites", "Memnite", "Memnite", "Memnite");
    private static final DeckList GUIDE = DeckList.of("guide", "Mountain", "Mountain", "Goblin Guide");
    private static final DeckList SWORDS = DeckList.of("swords", "Plains", "Plains", "Swords to Plowshares");
    private static final DeckList BOLT = DeckList.of("bolt", "Mountain", "Lightning Bolt", "Lightning Bolt");
    private static final DeckList ISLANDS = DeckList.of("islands", "Island", "Island", "Island");

    private static final Options OPTS = new Options(12, 12, 200_000, Duration.ofMinutes(5), false);

    private static void sameResult(DeckList a, DeckList b) {
        sameResult(a, b, OPTS);
    }

    private static void sameResult(DeckList a, DeckList b, Options opts) {
        for (int start = 0; start < 2; start++) {
            GameResult reduced = new Solver(a, b, start, opts).solve();
            GameResult full = new Solver(a, b, start, opts.withExhaustive(true)).solve();
            System.out.printf("%s vs %s (P%d first): %s in %d nodes; exhaustive %s in %d nodes%n", a.name(), b.name(),
                    start + 1, reduced.describe(), reduced.nodes(), full.describe(), full.nodes());
            assertTrue(reduced.complete() && full.complete(), "both searches should finish");
            assertEquals(full.pWin(), reduced.pWin(), 1e-9);
            assertEquals(full.pDraw(), reduced.pDraw(), 1e-9);
            assertEquals(full.pLoss(), reduced.pLoss(), 1e-9);
            assertTrue(reduced.nodes() <= full.nodes(), "reductions shouldn't search more");
        }
    }

    @Test
    void identicalAttackersAndRemovalTargets() {
        // Which Memnite attacks, and which one Swords exiles, doesn't matter.
        sameResult(MEMNITES, SWORDS);
    }

    @Test
    void identicalAttackersWin() {
        // A decisive game (three 1/1s need until turn 15 or so): the merged attack options must still find the win.
        GameResult r = new Solver(MEMNITES, ISLANDS, 0, new Options(20, 20, 200_000, Duration.ofMinutes(5), false)).solve();
        assertEquals(1, r.value(), 1e-9);
        sameResult(MEMNITES, ISLANDS, new Options(20, 20, 200_000, Duration.ofMinutes(5), false));
    }

    @Test
    void identicalBurnTargetsAndSpells() {
        // Two Bolts in hand, three Memnites to aim at (or the player).
        sameResult(BOLT, MEMNITES);
    }

    @Test
    void skippedDrawAndEndOfCombatWindows() {
        // Swords can be cast in any window; the skipped ones repeat the upkeep and damage step.
        sameResult(GUIDE, SWORDS);
    }
}
