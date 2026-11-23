package tcb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import tcb.deck.DeckList;
import tcb.engine.DecisionTape;
import tcb.engine.GameRunner;
import tcb.engine.GameRunner.Outcome;
import tcb.solve.Solver;
import tcb.solve.Solver.GameResult;
import tcb.solve.Solver.Options;

/**
 * The branching reductions (merging options that only swap identical cards, skipping priority
 * windows that repeat earlier ones, always taking opening-hand actions) must not change these
 * results: compare with exhaustive search.
 */
class ReductionsTest {
    private static final DeckList MEMNITES = DeckList.of("memnites", "Memnite", "Memnite", "Memnite");
    private static final DeckList GUIDE = DeckList.of("guide", "Mountain", "Mountain", "Goblin Guide");
    private static final DeckList SWORDS = DeckList.of("swords", "Plains", "Plains", "Swords to Plowshares");
    private static final DeckList BOLT = DeckList.of("bolt", "Mountain", "Lightning Bolt", "Lightning Bolt");
    private static final DeckList ISLANDS = DeckList.of("islands", "Island", "Island", "Island");
    private static final DeckList SANCTITY = DeckList.of("sanctity", "Leyline of Sanctity", "Plains", "Memnite");
    private static final DeckList CAVERNS = DeckList.of("caverns", "Gemstone Caverns", "Mountain", "Goblin Guide");
    private static final DeckList CONTROL_MAGIC = DeckList.of("control magic", "Island", "Island", "Control Magic");

    private static final Options OPTS = new Options(12, 12, 200_000, Duration.ofMinutes(5), false);

    private static void sameResult(DeckList a, DeckList b) {
        sameResult(a, b, OPTS);
    }

    private static void sameResult(DeckList a, DeckList b, Options opts) {
        for (int start = 0; start < 2; start++) {
            // narrowing off: it never changes results (NarrowingTest), but costs nodes on small games
            GameResult reduced = new Solver(a, b, start, opts.withNarrowing(false)).solve();
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
    @Tag("slow") // about 4 minutes: run with -Pslow
    void identicalBurnTargetsAndSpells() {
        // Two Bolts in hand, three Memnites to aim at (or the player).
        sameResult(BOLT, MEMNITES);
    }

    @Test
    void skippedDrawAndEndOfCombatWindows() {
        // Swords can be cast in any window; the skipped ones repeat the upkeep and damage step.
        sameResult(GUIDE, SWORDS);
    }

    @Test
    void openingHandActionsAreTaken() {
        // short limits: the opening hand is decided before turn 1, and these stay cheap to compare
        sameResult(SANCTITY, BOLT, new Options(4, 4, 200_000, Duration.ofMinutes(5), false));
        sameResult(CAVERNS, ISLANDS); // Gemstone Caverns: only on the draw, and costs a card
    }

    @Test
    void openingHandStillBranchesWhenControlCanChange() {
        assertEquals("opening hand", firstDecision(SANCTITY, CONTROL_MAGIC, false));
        assertTrue(!"opening hand".equals(firstDecision(SANCTITY, ISLANDS, false)), "Leyline always used");
        assertEquals("opening hand", firstDecision(SANCTITY, ISLANDS, true));
    }

    private static String firstDecision(DeckList a, DeckList b, boolean exhaustive) {
        Outcome o = new GameRunner(a, b, 0, exhaustive).run(new DecisionTape(new DecisionTape.Step[0], 12));
        return o.branch().kind();
    }
}
