package tcb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import tcb.deck.DeckList;
import tcb.engine.DecisionTape;
import tcb.engine.GameRunner;
import tcb.solve.GameReplay;
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
    void recordsTheSearchedTree() throws IOException {
        GameResult plain = new Solver(GUIDE, ISLANDS, 0, OPTS).solve();
        Solver solver = new Solver(GUIDE, ISLANDS, 0, OPTS);
        solver.recordTree();
        GameResult recorded = solver.solve();
        // Recording only watches: same result, same search.
        assertEquals(plain.value(), recorded.value(), 1e-9);
        assertEquals(plain.nodes(), recorded.nodes());
        assertEquals(plain.principalVariation(), recorded.principalVariation());

        JsonObject tree;
        try (var in = new InputStreamReader(new GZIPInputStream(new ByteArrayInputStream(solver.tree().toGzipJson())),
                StandardCharsets.UTF_8)) {
            tree = JsonParser.parseReader(in).getAsJsonObject();
        }
        assertEquals(15, tree.get("turnLimit").getAsInt());
        // A wins, so the first pass ("can A force a win?") settles it.
        assertEquals(1, tree.getAsJsonArray("passes").size());
        JsonObject root = tree.getAsJsonArray("passes").get(0).getAsJsonObject().getAsJsonObject("root");
        assertEquals(1, root.get("t").getAsInt());
        JsonObject best = null;
        for (var c : root.getAsJsonArray("c")) {
            if (c.getAsJsonObject().get("i").getAsInt() == root.get("best").getAsInt()) {
                best = c.getAsJsonObject();
            }
        }
        assertEquals(1, best.get("v").getAsDouble(), 1e-9, "the best first move wins: " + root);
    }

    @Test
    @SuppressWarnings("unchecked")
    void replaysTheOptimalLineWithTheBoard() {
        GameResult r = solve(GUIDE, ISLANDS, 0);
        Map<String, Object> replay = GameReplay.replay(GUIDE, ISLANDS, 0, r.principalVariation(), OPTS.maxTurns());
        List<Map<String, Object>> frames = (List<Map<String, Object>>) replay.get("frames");
        assertEquals("A wins on turn 10 (A's turn)", replay.get("end"));
        Map<String, Object> first = frames.get(0);
        assertEquals(1, first.get("turn"));
        assertEquals("Untap", first.get("phase"));
        assertEquals("A", first.get("active"));
        assertEquals(List.of(), first.get("did"));
        // turn 1 has no draw step, and B's turn, without an attack, has no blockers or damage steps
        assertEquals(List.of("Untap", "Upkeep", "Main phase 1", "Beginning of combat", "Declare attackers", "Declare blockers",
                "Combat damage", "End of combat", "Main phase 2", "End step", "Cleanup",
                "Untap", "Upkeep", "Draw", "Main phase 1", "Beginning of combat", "Declare attackers", "End of combat",
                "Main phase 2", "End step", "Cleanup", "Untap"),
                frames.stream().limit(22).map(f -> f.get("phase")).toList());
        assertEquals("B", frames.get(11).get("active"));
        assertEquals(List.of("A priority: Play Mountain", "A priority: Cast Goblin Guide"), frames.get(2).get("did"));
        // every decision of the line is in the step it was taken in, in order
        List<String> decisions = r.principalVariation().stream().filter(l -> !l.startsWith("=>") && !l.startsWith("(")).toList();
        assertEquals(decisions, frames.stream().flatMap(f -> ((List<String>) f.get("did")).stream()).toList());
        // the last board has B at 0 life or less
        Map<String, Object> last = frames.get(frames.size() - 1);
        Map<String, Object> b = ((List<Map<String, Object>>) last.get("players")).get(1);
        assertTrue((Integer) b.get("life") <= 0, "B is dead at the end: " + b);
        Map<String, Object> a = ((List<Map<String, Object>>) last.get("players")).get(0);
        assertTrue(((List<Map<String, Object>>) a.get("battlefield")).stream().anyMatch(c -> c.get("name").equals("Goblin Guide")));
    }

    @Test
    void endingCountsTurnCycles() {
        // Goblin Guide attacks on each of its own turns, 2 damage at a time: 10 attacks win on
        // turn 10, on the play or on the draw (turn cycles, not Forge's 19th or 20th turn).
        for (int start = 0; start < 2; start++) {
            List<String> line = solve(GUIDE, ISLANDS, start).principalVariation();
            assertEquals("=> A wins on turn 10 (A's turn)", line.get(line.size() - 1));
        }
    }

    @Test
    void searchesPastTheTurnLimitForASlowWin() {
        // One 1/1 needs 20 attacks: a win on turn 21, well past a limit of 6 turn cycles. A draw that
        // only comes from the limit is searched further (12, then 24 cycles), which finds the win.
        DeckList memnite = DeckList.of("memnite", "Memnite", "Island", "Island");
        GameResult r = new Solver(memnite, ISLANDS, 0, new Options(12, 12, 200_000, Duration.ofMinutes(5), false)).solve();
        assertEquals(1, r.value(), 1e-9);
        assertTrue(r.complete());
        assertEquals(48, r.provenTurnLimit());
        assertEquals("=> A wins on turn 21 (A's turn)", r.principalVariation().get(r.principalVariation().size() - 1));
    }

    @Test
    void aDrawPastTheExtendedLimitIsShownAtTheLimit() {
        // The same win is out of reach of 4 times a limit of 2 turn cycles, so it stays the draw at 2.
        DeckList memnite = DeckList.of("memnite", "Memnite", "Island", "Island");
        GameResult r = new Solver(memnite, ISLANDS, 0, new Options(4, 4, 200_000, Duration.ofMinutes(5), false)).solve();
        assertEquals(0, r.value(), 1e-9);
        assertTrue(r.complete());
        assertEquals(4, r.provenTurnLimit());
        assertEquals("=> draw (turn limit 2)", r.principalVariation().get(r.principalVariation().size() - 1));
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
