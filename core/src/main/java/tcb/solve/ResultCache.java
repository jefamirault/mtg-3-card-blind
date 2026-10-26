package tcb.solve;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.CodeSource;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;

import tcb.deck.DeckList;
import tcb.engine.ForgeEnv;
import tcb.solve.Solver.GameResult;
import tcb.solve.Solver.Options;

/**
 * Solved games on disk, one JSON file per game, so a tournament only solves pairings it hasn't
 * seen before.
 *
 * A game is keyed on the sorted card lists of both decks, who is on the play, the options that
 * change the result (turn limit, exact turns, exhaustive) and an engine fingerprint: a hash of
 * the compiled Forge and solver classes plus the Forge commit, so any change to Forge, the
 * patches or the solver starts a fresh cache. Deck names aren't part of the key.
 *
 * Budgets aren't part of the key either. A complete result is reused whatever the budget; an
 * unproven one only by a search whose node and time budgets are no bigger (and whose first turn
 * limit is the same), so a run with a bigger budget solves it again.
 */
public final class ResultCache {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static volatile String fingerprint;

    private final Path dir;
    /** Lookups since this cache was opened (see {@link #hits()}). */
    private final AtomicLong hits = new AtomicLong(), misses = new AtomicLong();

    public ResultCache(Path dir) {
        this.dir = dir;
    }

    public Path dir() {
        return dir;
    }

    /** Lookups since this cache was opened that found a usable result. */
    public long hits() {
        return hits.get();
    }

    /** Lookups since this cache was opened that didn't, so the game was solved. */
    public long misses() {
        return misses.get();
    }

    /** The cached result for this game, or null if there is none that these options can use. */
    public GameResult get(DeckList a, DeckList b, int startingPlayer, Options options) {
        GameResult r = read(file(a, b, startingPlayer, options), options);
        (r == null ? misses : hits).incrementAndGet();
        return r;
    }

    private static GameResult read(Path file, Options options) {
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            JsonObject o = GSON.fromJson(Files.readString(file), JsonObject.class);
            JsonObject r = o.getAsJsonObject("result");
            boolean complete = r.get("complete").getAsBoolean();
            if (!complete) {
                JsonObject budget = o.getAsJsonObject("budget");
                if (budget.get("nodes").getAsLong() < options.nodeBudget()
                        || budget.get("seconds").getAsDouble() < options.timeBudget().toMillis() / 1000.0
                        || budget.get("firstTurnLimit").getAsInt() != options.firstTurnLimit()) {
                    return null;
                }
            }
            List<String> line = new ArrayList<>();
            r.getAsJsonArray("line").forEach(e -> line.add(e.getAsString()));
            return new GameResult(r.get("pWin").getAsDouble(), r.get("pDraw").getAsDouble(), r.get("pLoss").getAsDouble(),
                    r.get("provenTurnLimit").getAsInt(), complete, List.copyOf(line), r.get("nodes").getAsLong(),
                    Duration.ofMillis(Math.round(r.get("seconds").getAsDouble() * 1000)));
        } catch (IOException | RuntimeException e) {
            // Unreadable or from an older format: solve the game again (and overwrite the file).
            return null;
        }
    }

    /**
     * Saves a result found by {@link Solver#solve()} with these options. A file that can't be
     * written only costs a re-solve next time, so that's a warning, not an error.
     */
    public void put(DeckList a, DeckList b, int startingPlayer, Options options, GameResult result) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("key", key(a, b, startingPlayer, options));
        root.put("budget", Map.of("nodes", options.nodeBudget(), "seconds", options.timeBudget().toMillis() / 1000.0,
                "firstTurnLimit", options.firstTurnLimit()));
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("pWin", result.pWin());
        r.put("pDraw", result.pDraw());
        r.put("pLoss", result.pLoss());
        r.put("provenTurnLimit", result.provenTurnLimit());
        r.put("complete", result.complete());
        r.put("nodes", result.nodes());
        r.put("seconds", result.elapsed().toMillis() / 1000.0);
        r.put("line", result.principalVariation());
        root.put("result", r);
        Path file = file(a, b, startingPlayer, options);
        try {
            Files.createDirectories(dir);
            // Write then rename, so a reader (another thread or process) never sees half a file.
            Path tmp = Files.createTempFile(dir, file.getFileName().toString(), ".tmp");
            try {
                Files.writeString(tmp, GSON.toJson(root));
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } finally {
                Files.deleteIfExists(tmp);
            }
        } catch (IOException e) {
            System.err.println("warning: can't write " + file + ": " + e);
        }
    }

    /**
     * The files in the cache and their total size, split by whether they were written by this
     * engine. Stale ones (another engine, or unreadable) can never be hit again.
     */
    public record Usage(int currentFiles, long currentBytes, int staleFiles, long staleBytes) {}

    public Usage usage() throws IOException {
        int currentFiles = 0, staleFiles = 0;
        long currentBytes = 0, staleBytes = 0;
        for (Path f : files()) {
            long size = Files.size(f);
            if (isStale(f)) {
                staleFiles++;
                staleBytes += size;
            } else {
                currentFiles++;
                currentBytes += size;
            }
        }
        return new Usage(currentFiles, currentBytes, staleFiles, staleBytes);
    }

    /** Deletes the stale files (see {@link #usage()}); returns how many files and bytes went. */
    public Usage clearStale() throws IOException {
        int files = 0;
        long bytes = 0;
        for (Path f : files()) {
            if (isStale(f)) {
                long size = Files.size(f);
                if (Files.deleteIfExists(f)) {
                    files++;
                    bytes += size;
                }
            }
        }
        return new Usage(0, 0, files, bytes);
    }

    /** The cache's result files (not the temporary ones {@link #put} writes first). */
    private List<Path> files() throws IOException {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(p -> p.toString().endsWith(".json") && Files.isRegularFile(p)).sorted().toList();
        }
    }

    private static boolean isStale(Path file) {
        try {
            JsonObject o = GSON.fromJson(Files.readString(file), JsonObject.class);
            return !o.getAsJsonObject("key").get("engine").getAsString().equals(fingerprint());
        } catch (IOException | RuntimeException e) {
            return true;
        }
    }

    private Path file(DeckList a, DeckList b, int startingPlayer, Options options) {
        String json = GSON.toJson(key(a, b, startingPlayer, options));
        return dir.resolve(sha256(json.getBytes(StandardCharsets.UTF_8)) + ".json");
    }

    /** Everything a game's result depends on, apart from the search budget. */
    private static Map<String, Object> key(DeckList a, DeckList b, int startingPlayer, Options options) {
        Map<String, Object> key = new LinkedHashMap<>();
        key.put("engine", fingerprint());
        key.put("a", a.cards().stream().sorted().toList());
        key.put("b", b.cards().stream().sorted().toList());
        key.put("startingPlayer", startingPlayer);
        key.put("maxTurns", options.maxTurns());
        key.put("exactTurns", options.exactTurns());
        key.put("exhaustive", options.exhaustive());
        return key;
    }

    /**
     * A hash of the class files of Forge's modules and of this project's core, plus the commit of
     * the Forge checkout (for the card scripts, which are read from its working tree). Only class
     * files are hashed: jar timestamps change on every build, compiled classes don't.
     */
    static String fingerprint() {
        String f = fingerprint;
        if (f == null) {
            synchronized (ResultCache.class) {
                f = fingerprint;
                if (f == null) {
                    f = computeFingerprint();
                    fingerprint = f;
                }
            }
        }
        return f;
    }

    private static String computeFingerprint() {
        MessageDigest md = digest();
        // One class from each module the solver runs: forge-core, forge-game, forge-ai, forge-gui, tcb-core.
        for (Class<?> c : List.of(forge.util.MyRandom.class, forge.game.Game.class, forge.ai.AiController.class,
                forge.gui.GuiBase.class, Solver.class)) {
            hashClasses(md, codeSource(c));
        }
        md.update(forgeCommit().getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(md.digest()).substring(0, 16);
    }

    private static Path codeSource(Class<?> c) {
        CodeSource cs = c.getProtectionDomain().getCodeSource();
        if (cs == null) {
            throw new IllegalStateException("can't locate the classes of " + c.getName());
        }
        try {
            return Path.of(cs.getLocation().toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Hashes every .class file in a jar or class directory, in name order. */
    private static void hashClasses(MessageDigest md, Path location) {
        Map<String, byte[]> classes = new TreeMap<>();
        try {
            if (Files.isDirectory(location)) {
                try (Stream<Path> files = Files.walk(location)) {
                    for (Path p : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".class"))::iterator) {
                        classes.put(location.relativize(p).toString().replace('\\', '/'), Files.readAllBytes(p));
                    }
                }
            } else {
                try (InputStream in = Files.newInputStream(location); ZipInputStream zip = new ZipInputStream(in)) {
                    for (ZipEntry e; (e = zip.getNextEntry()) != null; ) {
                        if (e.getName().endsWith(".class")) {
                            classes.put(e.getName(), zip.readAllBytes());
                        }
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("can't read " + location, e);
        }
        classes.forEach((name, bytes) -> {
            md.update(name.getBytes(StandardCharsets.UTF_8));
            md.update(sha256(bytes).getBytes(StandardCharsets.UTF_8));
        });
    }

    /** The commit checked out in the Forge submodule, or "" if it can't be found. */
    private static String forgeCommit() {
        try {
            Path forge = ForgeEnv.defaultForgeGuiDir().toAbsolutePath().normalize().getParent();
            Path git = forge.resolve(".git");
            if (Files.isRegularFile(git)) {
                // A submodule: ".git" is a file pointing at the real git directory.
                git = forge.resolve(Files.readString(git).strip().replaceFirst("^gitdir:\\s*", "")).normalize();
            }
            String head = Files.readString(git.resolve("HEAD")).strip();
            if (head.startsWith("ref: ")) {
                head = Files.readString(git.resolve(head.substring(5))).strip();
            }
            return head;
        } catch (IOException | RuntimeException e) {
            return "";
        }
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String sha256(byte[] bytes) {
        return HexFormat.of().formatHex(digest().digest(bytes));
    }
}
