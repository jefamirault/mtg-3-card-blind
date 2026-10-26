package tcb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import tcb.deck.DeckList;
import tcb.engine.ForgeEnv;
import tcb.solve.ResultCache;
import tcb.solve.Solver.GameResult;
import tcb.solve.Solver.Options;
import tcb.solve.Tournament;
import tcb.solve.Tournament.Results;

/** Reusing solved games across tournaments. */
class ResultCacheTest {
    private static final DeckList GUIDE = DeckList.of("guide", "Mountain", "Mountain", "Goblin Guide");
    private static final DeckList ISLANDS = DeckList.of("islands", "Island", "Island", "Island");
    private static final DeckList PLAINS = DeckList.of("plains", "Plains", "Plains", "Plains");

    private static final Options OPTS = new Options(30, 30, 200_000, Duration.ofMinutes(5), false);
    private static final GameResult WIN = new GameResult(1, 0, 0, 30, true, List.of("A plays Mountain", "=> A wins on turn 7"),
            1234, Duration.ofMillis(1500));
    private static final GameResult UNPROVEN = new GameResult(0, 1, 0, 0, false, List.of("..."), 200_000, Duration.ofSeconds(9));

    @TempDir
    Path dir;

    @Test
    void savedResultComesBack() {
        ResultCache cache = new ResultCache(dir);
        assertNull(cache.get(GUIDE, ISLANDS, 0, OPTS));
        cache.put(GUIDE, ISLANDS, 0, OPTS, WIN);
        assertEquals(WIN, cache.get(GUIDE, ISLANDS, 0, OPTS));
        // A new cache over the same folder (a later run) sees it too.
        assertEquals(WIN, new ResultCache(dir).get(GUIDE, ISLANDS, 0, OPTS));
    }

    @Test
    void keyIsCardsNotNamesOrOrder() {
        ResultCache cache = new ResultCache(dir);
        cache.put(GUIDE, ISLANDS, 0, OPTS, WIN);
        assertEquals(WIN, cache.get(DeckList.of("sligh", "Goblin Guide", "Mountain", "Mountain"), ISLANDS, 0, OPTS));
        assertNull(cache.get(GUIDE, ISLANDS, 1, OPTS), "other player on the play");
        assertNull(cache.get(ISLANDS, GUIDE, 0, OPTS), "decks swapped");
        assertNull(cache.get(GUIDE, PLAINS, 0, OPTS));
    }

    @Test
    void optionsThatChangeTheResultAreInTheKey() {
        ResultCache cache = new ResultCache(dir);
        cache.put(GUIDE, ISLANDS, 0, OPTS, WIN);
        assertNull(cache.get(GUIDE, ISLANDS, 0, new Options(40, 40, 200_000, Duration.ofMinutes(5), false)));
        assertNull(cache.get(GUIDE, ISLANDS, 0, new Options(30, 30, 200_000, Duration.ofMinutes(5), true)));
        assertNull(cache.get(GUIDE, ISLANDS, 0, OPTS.withExhaustive(true)));
        // A complete result doesn't depend on the budget.
        assertEquals(WIN, cache.get(GUIDE, ISLANDS, 0, new Options(30, 30, 5_000_000, Duration.ofHours(1), false)));
    }

    @Test
    void unprovenResultIsSolvedAgainWithABiggerBudget() {
        ResultCache cache = new ResultCache(dir);
        cache.put(GUIDE, ISLANDS, 0, OPTS, UNPROVEN);
        assertEquals(UNPROVEN, cache.get(GUIDE, ISLANDS, 0, OPTS));
        assertEquals(UNPROVEN, cache.get(GUIDE, ISLANDS, 0, new Options(30, 30, 100_000, Duration.ofMinutes(1), false)));
        assertNull(cache.get(GUIDE, ISLANDS, 0, new Options(30, 30, 400_000, Duration.ofMinutes(5), false)));
        assertNull(cache.get(GUIDE, ISLANDS, 0, new Options(30, 30, 200_000, Duration.ofMinutes(6), false)));
        assertNull(cache.get(GUIDE, ISLANDS, 0, new Options(30, 10, 200_000, Duration.ofMinutes(5), false)));
    }

    @Test
    void unreadableFileIsAMiss() throws Exception {
        ResultCache cache = new ResultCache(dir);
        cache.put(GUIDE, ISLANDS, 0, OPTS, WIN);
        try (Stream<Path> files = Files.list(dir)) {
            for (Path f : files.toList()) {
                Files.writeString(f, "{ not json");
            }
        }
        assertNull(cache.get(GUIDE, ISLANDS, 0, OPTS));
    }

    @Test
    void lookupsAreCounted() {
        ResultCache cache = new ResultCache(dir);
        cache.get(GUIDE, ISLANDS, 0, OPTS);
        cache.put(GUIDE, ISLANDS, 0, OPTS, WIN);
        cache.get(GUIDE, ISLANDS, 0, OPTS);
        cache.get(GUIDE, ISLANDS, 0, OPTS);
        assertEquals(2, cache.hits());
        assertEquals(1, cache.misses());
    }

    @Test
    void filesFromAnotherEngineAreStaleAndCanBeCleared() throws Exception {
        ResultCache cache = new ResultCache(dir);
        cache.put(GUIDE, ISLANDS, 0, OPTS, WIN);
        cache.put(GUIDE, PLAINS, 0, OPTS, WIN);
        Path old = dir.resolve("old.json");
        Files.writeString(old, "{\"key\": {\"engine\": \"0000000000000000\"}, \"result\": {}}");
        Path broken = dir.resolve("broken.json");
        Files.writeString(broken, "{ not json");
        Files.writeString(dir.resolve("leftover.json.tmp"), "half a file"); // not a result file
        long staleBytes = Files.size(old) + Files.size(broken);

        ResultCache.Usage u = cache.usage();
        assertEquals(2, u.currentFiles());
        assertEquals(2, u.staleFiles());
        assertEquals(staleBytes, u.staleBytes());

        ResultCache.Usage removed = cache.clearStale();
        assertEquals(2, removed.staleFiles());
        assertEquals(staleBytes, removed.staleBytes());
        assertFalse(Files.exists(old));
        assertFalse(Files.exists(broken));
        assertEquals(new ResultCache.Usage(2, u.currentBytes(), 0, 0), cache.usage());
        assertEquals(WIN, cache.get(GUIDE, ISLANDS, 0, OPTS));
    }

    @Test
    void secondTournamentOnlySolvesNewPairings() {
        ForgeEnv.init();
        ResultCache cache = new ResultCache(dir);
        Results first = Tournament.run(List.of(GUIDE, ISLANDS), OPTS, 2, line -> { },
                ConcurrentHashMap.newKeySet(), new Tournament.Stop(), cache);
        assertEquals(2, first.games().size());
        assertTrue(first.games().stream().noneMatch(Tournament.Game::cached));

        List<String> lines = new ArrayList<>();
        Results second = Tournament.run(List.of(GUIDE, ISLANDS, PLAINS), OPTS, 2, lines::add,
                ConcurrentHashMap.newKeySet(), new Tournament.Stop(), cache);
        assertEquals(6, second.games().size());
        for (Tournament.Game g : second.games()) {
            boolean old = g.b() == 1;
            assertEquals(old, g.cached(), g.a() + " vs " + g.b());
            if (old) {
                Tournament.Game before = first.games().stream()
                        .filter(f -> f.startingPlayer() == g.startingPlayer()).findFirst().orElseThrow();
                assertEquals(before.result().value(), g.result().value());
                assertEquals(before.result().principalVariation(), g.result().principalVariation());
                assertEquals(before.result().nodes(), g.result().nodes());
            }
        }
        assertEquals(2, lines.stream().filter(l -> l.endsWith("(cached)")).count());
        assertFalse(second.games().stream().anyMatch(g -> g.result().principalVariation().isEmpty()));
        assertNotNull(cache.get(ISLANDS, PLAINS, 1, OPTS));
    }
}
