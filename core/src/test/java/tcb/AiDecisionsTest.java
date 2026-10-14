package tcb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.function.ToIntFunction;

import org.junit.jupiter.api.Test;

import tcb.deck.DeckList;
import tcb.engine.DecisionTape;
import tcb.engine.GameRunner;
import tcb.engine.GameRunner.Outcome;
import tcb.engine.Signals.Branch;
import tcb.solve.Solver;
import tcb.solve.Solver.GameResult;
import tcb.solve.Solver.Options;

/**
 * Decisions that used to be left to Forge's AI heuristics are branch points now: the search
 * tries every option. Each test fails without the branching.
 */
class AiDecisionsTest {
    private static final DeckList LIONS = DeckList.of("lions", "Plains", "Plains", "Savannah Lions");
    private static final DeckList MEMNITES = DeckList.of("memnites", "Memnite", "Memnite", "Memnite");
    private static final DeckList MEMNITES_ISLAND = DeckList.of("memnites", "Memnite", "Memnite", "Island");

    private static final Options OPTS = new Options(30, 30, 200_000, Duration.ofMinutes(5), false);

    // ---------------------------------------------------------------- results that change

    @Test
    void xIsChosen() {
        // Blaze must be cast with X=1 to kill the Lions; Forge's AI left X at 0 and lost.
        DeckList blaze = DeckList.of("blaze", "Mountain", "Mountain", "Blaze");
        for (int start = 0; start < 2; start++) {
            GameResult r = new Solver(blaze, LIONS, start, OPTS).solve();
            assertEquals(0, r.value(), 1e-9, "Blaze holds off the Lions: " + r.principalVariation());
            assertTrue(r.principalVariation().stream().anyMatch(l -> l.contains("Cast Blaze with X=1 -> Savannah Lions")),
                    r.principalVariation().toString());
        }
    }

    @Test
    void chosenModesGetTargets() {
        // Izzet Charm's "2 damage to target creature" mode needs a target chosen with the mode;
        // without one it did nothing and the Lions won.
        DeckList charm = DeckList.of("charm", "Volcanic Island", "Volcanic Island", "Izzet Charm");
        GameResult r = new Solver(charm, LIONS, 0, OPTS).solve();
        assertEquals(0, r.value(), 1e-9, "Izzet Charm kills the Lions: " + r.principalVariation());
    }

    // ---------------------------------------------------------------- new branch points

    @Test
    void triggerTargets() {
        // Sparkmage Apprentice: "When this enters, it deals 1 damage to any target."
        DeckList sparkmage = DeckList.of("sparkmage", "Mountain", "Mountain", "Sparkmage Apprentice");
        Branch b = walkTo(sparkmage, MEMNITES_ISLAND, "trigger targets", AiDecisionsTest::first);
        // both players, the Apprentice itself, and one Memnite for the identical Memnites
        assertEquals(4, b.labels().size(), b.labels().toString());
    }

    @Test
    void manaPayment() {
        // Bolt can be paid with the Mox or the Mountain (keeping the other for later).
        DeckList mox = DeckList.of("mox", "Mox Ruby", "Mountain", "Lightning Bolt");
        Branch b = walkTo(mox, MEMNITES_ISLAND, "mana",
                script("Play Mountain", "Cast Mox Ruby", "Pass", "Cast Lightning Bolt"));
        assertEquals(2, b.labels().size(), b.labels().toString());
        assertTrue(b.labels().stream().anyMatch(l -> l.endsWith("with Mox Ruby")), b.labels().toString());
        assertTrue(b.labels().stream().anyMatch(l -> l.endsWith("with Mountain")), b.labels().toString());
    }

    @Test
    void openingHandActions() {
        DeckList leyline = DeckList.of("leyline", "Leyline of Sanctity", "Plains", "Plains");
        Branch b = walkTo(leyline, MEMNITES_ISLAND, "opening hand", AiDecisionsTest::first);
        assertEquals(List.of("From opening hand: Leyline of Sanctity", "From opening hand: nothing"), b.labels());
    }

    @Test
    void dividedDamage() {
        // Forked Bolt: 2 damage divided among one or two targets.
        DeckList forked = DeckList.of("forked", "Mountain", "Mountain", "Forked Bolt");
        Branch b = walkTo(forked, MEMNITES_ISLAND, "priority", AiDecisionsTest::first, l -> l.contains("Forked Bolt"));
        assertTrue(b.labels().stream().anyMatch(l -> l.contains(": 1 & ")), "one damage to each of two targets: " + b.labels());
        assertTrue(b.labels().stream().anyMatch(l -> l.matches(".*-> [^&]*: 2")), "two damage to one target: " + b.labels());
    }

    @Test
    void combatDamageAssignment() {
        // A 3/2 trampler blocked by two 1/1s: kill both and trample over for 1, or not.
        DeckList companion = DeckList.of("companion", "Forest", "Forest", "Garruk's Companion");
        // Memnites stay home to block, with as many as possible.
        ToIntFunction<Branch> policy = b -> b.player() == 1 && b.kind().equals("attack") ? b.labels().size() - 1 : 0;
        Branch b = walkTo(companion, MEMNITES_ISLAND, "damage assignment", policy);
        // 3+0, 2+1 (the Memnites are identical, so 1+2 is the same), and 1+1 with 1 to the player
        assertEquals(3, b.labels().size(), b.labels().toString());
        assertTrue(b.labels().stream().anyMatch(l -> l.contains("to B")), "trample damage to the player: " + b.labels());
    }

    // ---------------------------------------------------------------- helpers

    private static int first(Branch b) {
        return 0;
    }

    /** Takes the scripted options in order (each the first label starting with it), else the first option. */
    private static ToIntFunction<Branch> script(String... steps) {
        int[] next = {0};
        return b -> {
            if (next[0] < steps.length) {
                for (int i = 0; i < b.labels().size(); i++) {
                    if (b.labels().get(i).startsWith(steps[next[0]])) {
                        next[0]++;
                        return i;
                    }
                }
            }
            return b.labels().size() - 1; // pass / no attack
        };
    }

    private static Branch walkTo(DeckList a, DeckList b, String kind, ToIntFunction<Branch> policy) {
        return walkTo(a, b, kind, policy, l -> true);
    }

    /** Plays A on the play following {@code policy} until a decision of {@code kind} with a matching label. */
    private static Branch walkTo(DeckList a, DeckList b, String kind, ToIntFunction<Branch> policy,
                                 java.util.function.Predicate<String> label) {
        GameRunner runner = new GameRunner(a, b, 0);
        int[] tape = new int[0];
        for (int step = 0; step < 300; step++) {
            Outcome o = runner.run(new DecisionTape(tape, new int[tape.length], 30));
            if (o.isTerminal()) {
                break;
            }
            Branch branch = o.branch();
            if (branch.kind().equals(kind) && branch.labels().stream().anyMatch(label)) {
                return branch;
            }
            tape = Arrays.copyOf(tape, tape.length + 1);
            tape[tape.length - 1] = policy.applyAsInt(branch);
        }
        assertNotNull(null, "no " + kind + " decision reached");
        return null;
    }
}
