package tcb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import tcb.deck.DeckList;
import tcb.engine.DecisionTape;
import tcb.engine.GameRunner;
import tcb.engine.GameRunner.Outcome;
import tcb.engine.Signals;
import tcb.solve.Solver;
import tcb.solve.Solver.GameResult;
import tcb.solve.Solver.Options;

/**
 * The branching reductions (merging options that only swap identical cards, always taking
 * opening-hand actions) must not change these results: compare with exhaustive search.
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
    void instantsInEveryWindow() {
        // Swords can be cast in any window.
        sameResult(GUIDE, SWORDS);
    }

    @Test
    void noPriorityWindowIsSkipped() {
        // Desert's damage ability works only in the end of combat step, which comes right after
        // combat damage: that window must still be offered.
        DeckList deserts = DeckList.of("deserts", "Desert", "Desert", "Desert");
        assertOffered(MEMNITES, deserts, b -> b.labels().stream()
                .anyMatch(l -> l.startsWith("Activate Desert") && l.contains("damage")));
    }

    @Test
    void combatDamageStepWithoutDamageGivesPriority() {
        // Only a first striker fights, so nothing deals damage in the regular combat damage step.
        // Players still get priority there (CR 510.3; patch 0006).
        DeckList knight = DeckList.of("knight", "Plains", "Plains", "Youthful Knight");
        assertOffered(knight, SWORDS, b -> b.position() != null && b.position().contains("|phase=COMBAT_DAMAGE|"));
    }

    @Test
    void zeroPowerAttackGivesPriority() {
        // An Ornithopter (0/2) attack: priority in the declare attackers step, and in the combat
        // damage step though nothing deals damage (patch 0006).
        DeckList thopter = DeckList.of("thopter", "Plains", "Plains", "Ornithopter");
        assertOffered(thopter, SWORDS, b -> b.player() == 1 && b.position() != null
                && b.position().contains("|phase=COMBAT_DECLARE_ATTACKERS|"));
        assertOffered(thopter, SWORDS, b -> b.player() == 1 && b.position() != null
                && b.position().contains("|phase=COMBAT_DAMAGE|"));
    }

    /**
     * Plays a game, acting first (lands, spells, the biggest attack) but passing once only
     * activations are left, until a priority decision matching {@code wanted} is offered.
     */
    private static void assertOffered(DeckList a, DeckList b, java.util.function.Predicate<Signals.Branch> wanted) {
        GameRunner runner = new GameRunner(a, b, 0, false);
        List<DecisionTape.Step> steps = new ArrayList<>();
        for (int n = 0; n < 300; n++) {
            Outcome o = runner.run(new DecisionTape(steps.toArray(new DecisionTape.Step[0]), 12));
            Signals.Branch br = o.branch();
            assertTrue(br != null, "game ended before the window was offered");
            boolean priority = "priority".equals(br.kind());
            if (priority && wanted.test(br)) {
                return;
            }
            String first = br.labels().get(0);
            boolean act = !priority || first.startsWith("Play") || (br.player() == 0 && first.startsWith("Cast"));
            steps.add(br.step(act ? 0 : br.labels().size() - 1));
        }
        throw new AssertionError("the window was never offered");
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
