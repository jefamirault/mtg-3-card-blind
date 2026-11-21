package tcb.web;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;

import tcb.engine.ForgeEnv;
import tcb.engine.NondeterministicReplayException;

/**
 * Writes the public site: the web page in its read-only mode plus static JSON for the saved
 * tournaments, so the site can show results and play games back without Forge or a solver.
 *
 * <pre>java ... tcb.web.SiteExport [--history history] [--out site/public] [--only ID,ID] [--trees] [--threads N]</pre>
 *
 * Output, all relative to {@code --out}: {@code index.html}; {@code data/history.json} (the list);
 * {@code data/history/<id>.json} (as saved); {@code data/replays/<id>/<k>.json.gz} (game k's boards, as
 * the server's replay endpoint answers, gzipped); {@code data/trees/<id>/<k>.json.gz} with {@code --trees};
 * {@code data/cards.json} (Vintage-legal card names, for the request form). The export updates
 * the folder in place: a replay whose tournament file hasn't changed is kept rather than replayed
 * again, and files under {@code data/} it didn't write are deleted.
 */
public final class SiteExport {
    private static final Gson GSON = new GsonBuilder().serializeNulls().create();

    private final Path history;
    private final Path out;
    private final boolean trees;
    private final int threads;
    private final Set<Path> written = new HashSet<>();

    private SiteExport(Path history, Path out, boolean trees, int threads) {
        this.history = history;
        this.out = out;
        this.trees = trees;
        this.threads = threads;
    }

    public static void main(String[] args) throws Exception {
        Path history = Path.of("history");
        Path out = Path.of("site", "public");
        Set<String> only = null;
        boolean trees = false;
        int threads = Runtime.getRuntime().availableProcessors();
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--trees")) {
                trees = true;
                continue;
            }
            if (i + 1 >= args.length) {
                throw new IllegalArgumentException("missing value for " + args[i]);
            }
            String value = args[++i];
            switch (args[i - 1]) {
                case "--history" -> history = Path.of(value);
                case "--out" -> out = Path.of(value);
                case "--only" -> only = new HashSet<>(Arrays.asList(value.split(",")));
                case "--threads" -> threads = Integer.parseInt(value);
                default -> throw new IllegalArgumentException("unknown option " + args[i - 1]);
            }
        }
        ForgeEnv.init();
        new SiteExport(history, out, trees, threads).run(only);
        System.exit(0); // Forge leaves non-daemon threads behind
    }

    private void run(Set<String> only) throws Exception {
        List<Path> files;
        try (Stream<Path> s = Files.list(history)) {
            files = s.filter(p -> p.getFileName().toString().endsWith(".json")).sorted(Comparator.reverseOrder()).toList();
        }
        List<Map<String, Object>> list = new ArrayList<>();
        int failed = 0;
        for (Path f : files) {
            String id = f.getFileName().toString().replaceFirst("\\.json$", "");
            if (only != null && !only.contains(id)) {
                continue;
            }
            JsonObject saved = GSON.fromJson(Files.readString(f), JsonObject.class);
            failed += tournament(id, saved);
            list.add(SavedTournament.summary(saved));
        }
        write(out.resolve("data/history.json"), GSON.toJson(list));
        write(out.resolve("data/cards.json"), GSON.toJson(SavedTournament.vintageCardNames()));
        write(out.resolve("index.html"), publicPage());
        int removed = removeUnwritten();
        System.err.printf("Exported %d tournaments to %s%s%s%n", list.size(), out,
                failed > 0 ? "; " + failed + " games can't be replayed any more and have no replay" : "",
                removed > 0 ? "; removed " + removed + " old files" : "");
    }

    /** Writes one tournament and its replays; returns how many games couldn't be replayed. */
    private int tournament(String id, JsonObject saved) throws Exception {
        Path treesIn = history.resolve(id + ".trees");
        boolean withTrees = trees && Files.isDirectory(treesIn);
        saved.addProperty("trees", withTrees);
        Path file = out.resolve("data/history/" + id + ".json");
        // Replays depend only on the saved file, so an unchanged one keeps the replays from last time
        // (a game that failed to replay has no file, so it's tried again).
        boolean unchanged = Files.exists(file) && sameGames(GSON.fromJson(Files.readString(file), JsonObject.class), saved);

        int n = SavedTournament.games(saved);
        List<Integer> todo = new ArrayList<>();
        for (int k = 0; k < n; k++) {
            Path replay = replayFile(id, k);
            if (!SavedTournament.hasLine(saved, k)) {
                continue;
            }
            if (unchanged && Files.exists(replay)) {
                written.add(replay.toAbsolutePath().normalize());
            } else {
                todo.add(k);
            }
        }
        AtomicInteger done = new AtomicInteger();
        List<Integer> failed = new ArrayList<>();
        ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, threads));
        try {
            List<Future<?>> jobs = new ArrayList<>();
            for (int k : todo) {
                jobs.add(pool.submit(() -> {
                    try {
                        writeGzip(replayFile(id, k), GSON.toJson(SavedTournament.replay(saved, k)));
                    } catch (RuntimeException e) {
                        if (!NondeterministicReplayException.causedBy(e)) {
                            throw e;
                        }
                        synchronized (failed) {
                            failed.add(k);
                        }
                    }
                    int d = done.incrementAndGet();
                    if (d % 25 == 0 || d == todo.size()) {
                        System.err.printf("  %s: replayed %d of %d games%n", id, d, todo.size());
                    }
                    return null;
                }));
            }
            for (Future<?> j : jobs) {
                j.get();
            }
        } finally {
            pool.shutdownNow();
        }
        var games = saved.getAsJsonObject("results").getAsJsonArray("games");
        for (int k : failed) {
            games.get(k).getAsJsonObject().addProperty("replay", false);
        }
        write(file, GSON.toJson(saved));

        if (withTrees) {
            try (Stream<Path> s = Files.list(treesIn)) {
                for (Path t : s.filter(p -> p.getFileName().toString().endsWith(".json.gz")).toList()) {
                    Path dest = out.resolve("data/trees/" + id + "/" + t.getFileName());
                    Files.createDirectories(dest.getParent());
                    Files.copy(t, dest, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    written.add(dest.toAbsolutePath().normalize());
                }
            }
        }
        return failed.size();
    }

    /** Whether two copies of a saved tournament have the same games (ignoring the replay marks). */
    private static boolean sameGames(JsonObject previous, JsonObject saved) {
        JsonObject a = previous.deepCopy();
        for (var g : a.getAsJsonObject("results").getAsJsonArray("games")) {
            g.getAsJsonObject().remove("replay");
        }
        a.add("trees", saved.get("trees"));
        return a.equals(saved);
    }

    private Path replayFile(String id, int k) {
        return out.resolve("data/replays/" + id + "/" + k + ".json.gz");
    }

    /** The web page in its public mode: read-only, reading the files above instead of the API. */
    static String publicPage() throws IOException {
        String page;
        try (InputStream in = SiteExport.class.getResourceAsStream("/web/index.html")) {
            page = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        String tag = "<html lang=\"en\">";
        if (!page.contains(tag)) {
            throw new IllegalStateException("index.html has no " + tag + " to mark as the public page");
        }
        return page.replaceFirst(tag, "<html lang=\"en\" data-mode=\"public\">");
    }

    /** Replays are gzipped (they shrink about 80 times); the page unzips them. */
    private void writeGzip(Path file, String text) throws IOException {
        Files.createDirectories(file.getParent());
        try (var gz = new java.util.zip.GZIPOutputStream(Files.newOutputStream(file))) {
            gz.write(text.getBytes(StandardCharsets.UTF_8));
        }
        synchronized (written) {
            written.add(file.toAbsolutePath().normalize());
        }
    }

    private void write(Path file, String text) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
        synchronized (written) {
            written.add(file.toAbsolutePath().normalize());
        }
    }

    /** Deletes files under data/ that this export didn't write, and the folders left empty. */
    private int removeUnwritten() throws IOException {
        Path data = out.resolve("data");
        int removed = 0;
        try (Stream<Path> s = Files.walk(data)) {
            for (Path p : s.sorted(Comparator.reverseOrder()).toList()) {
                if (Files.isRegularFile(p) && !written.contains(p.toAbsolutePath().normalize())) {
                    Files.delete(p);
                    removed++;
                } else if (Files.isDirectory(p) && !p.equals(data)) {
                    try (Stream<Path> children = Files.list(p)) {
                        if (children.findAny().isEmpty()) {
                            Files.delete(p);
                        }
                    }
                }
            }
        }
        return removed;
    }
}
