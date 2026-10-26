package tcb.web;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import tcb.deck.EntryFile;
import tcb.solve.ResultCache;

/**
 * What the app stores, for the stats page: the entries, the saved tournaments (and their search
 * trees) and the result cache, with the space each takes. Only the files the app writes in those
 * directories are counted. They're small, so this walks them on every request.
 */
final class DataStats {
    private static final Gson GSON = new Gson();
    private static final int TOP_CARDS = 8;

    private DataStats() {
    }

    /** @param cache null when the server runs with --no-cache */
    static Map<String, Object> compute(Path entriesDir, Path historyDir, ResultCache cache) throws IOException {
        Map<String, Object> entries = entries(entriesDir);
        Map<String, Object> history = history(historyDir);
        Map<String, Object> cacheStats = cache == null ? null : cache(cache);
        long total = (long) entries.get("bytes") + (long) history.get("bytes") + (long) history.get("treeBytes")
                + (cacheStats == null ? 0 : (long) cacheStats.get("currentBytes") + (long) cacheStats.get("staleBytes"));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("totalBytes", total);
        m.put("entries", entries);
        m.put("history", history);
        m.put("cache", cacheStats);
        return m;
    }

    private static Map<String, Object> entries(Path dir) throws IOException {
        int count = 0, withNotes = 0, undated = 0;
        long bytes = 0;
        Map<String, int[]> cards = new HashMap<>(); // card -> {copies, entries}
        Map<String, Object> oldest = null, newest = null;
        Instant oldestAt = null, newestAt = null;
        for (Path f : list(dir, ".txt")) {
            EntryFile entry = EntryFile.read(f);
            count++;
            bytes += Files.size(f);
            if (!entry.notes().isBlank()) {
                withNotes++;
            }
            if (entry.created() == null) {
                undated++;
            } else {
                if (oldestAt == null || entry.created().isBefore(oldestAt)) {
                    oldestAt = entry.created();
                    oldest = Map.of("name", entry.deck().name(), "created", oldestAt.toString());
                }
                if (newestAt == null || entry.created().isAfter(newestAt)) {
                    newestAt = entry.created();
                    newest = Map.of("name", entry.deck().name(), "created", newestAt.toString());
                }
            }
            for (String card : entry.deck().cards()) {
                cards.computeIfAbsent(card, c -> new int[2])[0]++;
            }
            entry.deck().cards().stream().distinct().forEach(card -> cards.get(card)[1]++);
        }
        List<Map<String, Object>> top = cards.entrySet().stream()
                .sorted(Comparator.<Map.Entry<String, int[]>>comparingInt(e -> -e.getValue()[1])
                        .thenComparingInt(e -> -e.getValue()[0])
                        .thenComparing(Map.Entry::getKey))
                .limit(TOP_CARDS)
                .map(e -> Map.<String, Object>of("name", e.getKey(), "entries", e.getValue()[1], "copies", e.getValue()[0]))
                .toList();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("count", count);
        m.put("bytes", bytes);
        m.put("withNotes", withNotes);
        m.put("undated", undated);
        m.put("distinctCards", cards.size());
        m.put("topCards", top);
        m.put("oldest", oldest);
        m.put("newest", newest);
        return m;
    }

    private static Map<String, Object> history(Path dir) throws IOException {
        int count = 0, games = 0, proven = 0, cached = 0, notPlayed = 0;
        long bytes = 0;
        double seconds = 0;
        Map<String, Object> largest = null, slowest = null;
        int largestField = 0;
        double slowestSeconds = -1;
        Map<String, String> finished = new HashMap<>();
        for (Path f : list(dir, ".json")) {
            count++;
            bytes += Files.size(f);
            JsonObject saved = GSON.fromJson(Files.readString(f), JsonObject.class);
            String id = saved.get("id").getAsString();
            String when = saved.get("finished").getAsString();
            finished.put(id, when);
            seconds += saved.get("seconds").getAsDouble();
            JsonObject results = saved.getAsJsonObject("results");
            int field = results.getAsJsonArray("entries").size();
            if (field > largestField) {
                largestField = field;
                largest = Map.of("id", id, "finished", when, "entries", field);
            }
            if (results.has("notPlayed")) {
                notPlayed += results.getAsJsonArray("notPlayed").size();
            }
            for (JsonElement e : results.getAsJsonArray("games")) {
                JsonObject g = e.getAsJsonObject();
                games++;
                if (g.get("complete").getAsBoolean()) {
                    proven++;
                }
                if (g.has("cached") && g.get("cached").getAsBoolean()) {
                    cached++;
                }
                double s = g.get("seconds").getAsDouble();
                if (s > slowestSeconds) {
                    slowestSeconds = s;
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", id);
                    m.put("finished", when);
                    m.put("a", g.get("a").getAsString());
                    m.put("b", g.get("b").getAsString());
                    m.put("onThePlay", g.get("onThePlay").getAsString());
                    m.put("seconds", s);
                    m.put("nodes", g.get("nodes").getAsLong());
                    m.put("complete", g.get("complete").getAsBoolean());
                    slowest = m;
                }
            }
        }
        // Search trees: <id>.trees/<k>.json.gz, biggest first.
        List<Map<String, Object>> trees = new ArrayList<>();
        long treeBytes = 0;
        int treeFiles = 0;
        for (Path d : listDirs(dir, ".trees")) {
            String id = d.getFileName().toString().replaceFirst("\\.trees$", "");
            long size = 0;
            int files = 0;
            for (Path t : list(d, ".json.gz")) {
                size += Files.size(t);
                files++;
            }
            treeBytes += size;
            treeFiles += files;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", id);
            m.put("finished", finished.get(id)); // null for trees left without their tournament
            m.put("files", files);
            m.put("bytes", size);
            trees.add(m);
        }
        trees.sort(Comparator.comparingLong((Map<String, Object> m) -> (long) m.get("bytes")).reversed());
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("count", count);
        m.put("bytes", bytes);
        m.put("treeBytes", treeBytes);
        m.put("treeFiles", treeFiles);
        m.put("trees", trees);
        m.put("games", games);
        m.put("proven", proven);
        m.put("cached", cached);
        m.put("notPlayed", notPlayed);
        m.put("seconds", seconds);
        m.put("largest", largest);
        m.put("slowest", slowest);
        return m;
    }

    private static Map<String, Object> cache(ResultCache cache) throws IOException {
        ResultCache.Usage u = cache.usage();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("currentFiles", u.currentFiles());
        m.put("currentBytes", u.currentBytes());
        m.put("staleFiles", u.staleFiles());
        m.put("staleBytes", u.staleBytes());
        m.put("hits", cache.hits());
        m.put("misses", cache.misses());
        return m;
    }

    private static List<Path> list(Path dir, String suffix) throws IOException {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(p -> p.getFileName().toString().endsWith(suffix) && Files.isRegularFile(p)).sorted().toList();
        }
    }

    private static List<Path> listDirs(Path dir, String suffix) throws IOException {
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(p -> p.getFileName().toString().endsWith(suffix) && Files.isDirectory(p)).sorted().toList();
        }
    }
}
