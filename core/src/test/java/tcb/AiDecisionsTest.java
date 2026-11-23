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
        // Against a deck that can take the Leyline, keeping it in hand is searched too (otherwise
        // the opening-hand rule always uses it).
        DeckList leyline = DeckList.of("leyline", "Leyline of Sanctity", "Plains", "Plains");
        DeckList controlMagic = DeckList.of("control magic", "Island", "Island", "Control Magic");
        Branch b = walkTo(leyline, controlMagic, "opening hand", AiDecisionsTest::first);
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

    @Test
    void storageCountersPayForSpells() {
        // Fountain of Cho's "{T}, Remove any number of storage counters: Add {W} for each" pays
        // for Chancellor of the Annex once it has 8 counters (issue #22). Forge's AI only used it
        // as a ritual, so the spell never showed up as castable.
        DeckList fountain = DeckList.of("fountain", "Chancellor of the Annex", "Fountain of Cho", "Island");
        DeckList islands = DeckList.of("islands", "Island", "Island", "Island");
        // play the Fountain, and store a counter on the opponent's turns so it's untapped on ours
        Branch b = walkTo(fountain, islands, "priority", br -> {
            for (int i = 0; i < br.labels().size(); i++) {
                String l = br.labels().get(i);
                if (l.startsWith("Play Fountain") || (l.contains("Put a storage counter") && br.turn() % 2 == 0)) {
                    return i;
                }
            }
            return br.labels().size() - 1;
        }, l -> l.startsWith("Cast Chancellor of the Annex"));
        assertTrue(b.turn() <= 19, "8 counters by A's 9th turn, cast on A's 10th: turn " + b.turn());
    }

    @Test
    void announcedXIsChosen() {
        // Sickening Shoal's alternative cost exiles a black card with mana value X, announced on
        // casting. Forge's AI casting path never announced it, so the cost couldn't be paid and the
        // AI retried it until Forge gave up ("AI looped too much").
        DeckList shoal = DeckList.of("shoal", "Khan, Vengeful Heart", "Sickening Shoal", "Swamp");
        // B plays Plains and casts the Lions; A passes until Shoal can target them
        Branch b = walkTo(shoal, LIONS, "priority",
                br -> br.player() == 1 ? 0 : br.labels().size() - 1,
                l -> l.contains("Sickening Shoal") && l.contains("-> Savannah Lions"));
        List<String> shoals = b.labels().stream().filter(l -> l.contains("Sickening Shoal")).toList();
        // Khan's mana value is 4: X=4 is the only value the exile can pay for
        assertEquals(1, shoals.size(), shoals.toString());
        assertTrue(shoals.get(0).startsWith("Cast Sickening Shoal with X=4 -> Savannah Lions"), shoals.toString());
        // and the cast goes through: the Lions die, so they can't win
        GameResult r = new Solver(shoal, LIONS, 0, OPTS).solve();
        assertTrue(r.value() >= 0, "Shoal holds off the Lions: " + r.principalVariation());
        assertTrue(r.principalVariation().stream().anyMatch(l -> l.contains("Cast Sickening Shoal with X=4")),
                r.principalVariation().toString());
    }

    @Test
    void manaAbilitiesWithSideEffects() {
        // Pristine Talisman ("Add {C}. You gain 1 life.") is worth tapping for the life alone, so it's
        // offered at priority; plain mana sources (the Island) are tapped only to pay for something.
        DeckList talisman = DeckList.of("talisman", "Black Lotus", "Pristine Talisman", "Island");
        Branch b = walkTo(talisman, MEMNITES, "priority", script("Play Island", "Cast Black Lotus", "Cast Pristine Talisman"),
                l -> l.startsWith("Activate Pristine Talisman"));
        assertTrue(b.labels().stream().noneMatch(l -> l.startsWith("Activate Island") || l.startsWith("Activate Black Lotus")),
                b.labels().toString());
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
