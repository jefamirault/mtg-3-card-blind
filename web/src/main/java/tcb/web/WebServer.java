package tcb.web;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.function.Predicate;
import java.util.stream.Stream;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import forge.game.GameFormat;
import forge.item.PaperCard;
import forge.model.FModel;
import tcb.deck.DeckList;
import tcb.deck.EntryFile;
import tcb.deck.EntryImport;
import tcb.deck.VintageValidator;
import tcb.engine.ForgeEnv;
import tcb.engine.NondeterministicReplayException;
import tcb.solve.GameReplay;
import tcb.solve.ResultCache;
import tcb.solve.ResultsJson;
import tcb.solve.Solver;
import tcb.solve.Tournament;
import tcb.solve.Turns;

/**
 * Web front end: submit entries, run the tournament, browse results.
 * Entries are stored as deck files in a directory, so the CLI can use the same folder.
 * Each finished tournament is saved as a JSON file in the history directory.
 * On first launch (no entries directory yet) the entries start as copies of the deck files in
 * the {@code tcb.samplesDir} system property's directory, if it's set.
 *
 * <pre>java ... tcb.web.WebServer [--port 8080] [--entries entries] [--history history] [--cache cache | --no-cache] [--threads N]</pre>
 */
public final class WebServer {
    private static final Gson GSON = new GsonBuilder().serializeNulls().create();
    private static final int MAX_NAME = 40;
    private static final int MAX_NOTES = 2000;
    private static final Solver.Options DEFAULTS = Solver.Options.defaults();
    private static final int MAX_TIME_SECONDS = 24 * 60 * 60;
    private static final long MIN_NODES = 1_000, MAX_NODES = 1_000_000_000;
    private static final int MAX_TURNS = 100; // turn cycles
    /** Most entries a tournament that saves search trees can have (8 entries: 56 games). */
    private static final int MAX_TREE_ENTRIES = 8;
    private static final DateTimeFormatter ID_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final Path entriesDir;
    private final Path historyDir;
    /** Solved games to reuse; null with --no-cache. */
    private final ResultCache cache;
    private final int threads;
    private final List<String> cardNames;

    // tournament state, guarded by this
    private String state = "idle"; // idle | running | stopping | done | stopped | failed
    private final List<String> progress = new CopyOnWriteArrayList<>();
    private final Set<Tournament.Running> running = ConcurrentHashMap.newKeySet();
    private Tournament.Stop stop = new Tournament.Stop();
    private Solver.Options options = DEFAULTS;
    private boolean saveTrees;
    private List<DeckList> tournamentEntries = List.of();
    private int totalGames;
    private Map<String, Object> results;
    private String error;

    WebServer(Path entriesDir, Path historyDir, ResultCache cache, int threads) {
        this.entriesDir = entriesDir;
        this.historyDir = historyDir;
        this.cache = cache;
        this.threads = threads;
        // Vintage-legal card names for autocomplete
        GameFormat vintage = FModel.getFormats().getVintage();
        Predicate<PaperCard> legal = vintage.getFilterRules();
        TreeSet<String> names = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (PaperCard card : FModel.getMagicDb().getCommonCards().getUniqueCards()) {
            if (legal.test(card) && !vintage.getBannedCardNames().contains(card.getName())
                    && VintageValidator.hasTournamentLegalPrinting(vintage, card.getName())) {
                names.add(card.getName());
            }
        }
        this.cardNames = new ArrayList<>(names);
    }

    public static void main(String[] args) throws IOException {
        int port = 8080;
        Path entries = Path.of("entries");
        Path history = Path.of("history");
        Path cache = Path.of("cache");
        int threads = Runtime.getRuntime().availableProcessors();
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--no-cache")) {
                cache = null;
                continue;
            }
            if (i + 1 >= args.length) {
                throw new IllegalArgumentException("missing value for " + args[i]);
            }
            String value = args[++i];
            switch (args[i - 1]) {
                case "--port" -> port = Integer.parseInt(value);
                case "--entries" -> entries = Path.of(value);
                case "--history" -> history = Path.of(value);
                case "--cache" -> cache = Path.of(value);
                case "--threads" -> threads = Integer.parseInt(value);
                default -> throw new IllegalArgumentException("unknown option " + args[i - 1]);
            }
        }
        if (!Files.exists(entries)) {
            Files.createDirectories(entries);
            copySamples(entries);
        }
        Files.createDirectories(history);
        ForgeEnv.init();
        WebServer app = new WebServer(entries, history, cache == null ? null : new ResultCache(cache), threads);
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/", app::handle);
        server.setExecutor(Executors.newFixedThreadPool(8));
        server.start();
        System.err.println("3 Card Blind: http://localhost:" + port + "/  (entries in " + entries.toAbsolutePath() + ")");
    }

    /** Fills a new entries directory with the sample decks, so a fresh clone has a field to try. */
    private static void copySamples(Path entries) throws IOException {
        String dir = System.getProperty("tcb.samplesDir");
        if (dir == null || !Files.isDirectory(Path.of(dir))) {
            return;
        }
        try (Stream<Path> files = Files.list(Path.of(dir))) {
            for (Path f : files.filter(p -> p.toString().endsWith(".txt")).toList()) {
                Files.copy(f, entries.resolve(f.getFileName()));
            }
        }
    }

    private void handle(HttpExchange ex) throws IOException {
        try {
            String path = ex.getRequestURI().getPath();
            String method = ex.getRequestMethod();
            if (path.equals("/") || path.equals("/index.html")) {
                sendResource(ex, "/web/index.html", "text/html; charset=utf-8");
            } else if (path.equals("/api/cards") && method.equals("GET")) {
                sendJson(ex, 200, searchCards(query(ex).getOrDefault("q", "")));
            } else if (path.equals("/api/entries") && method.equals("GET")) {
                sendJson(ex, 200, listEntries());
            } else if (path.equals("/api/entries/import") && method.equals("POST")) {
                importEntries(ex);
            } else if (path.equals("/api/entries/export") && method.equals("GET")) {
                exportEntries(ex);
            } else if (path.equals("/api/entries") && method.equals("POST")) {
                saveEntry(ex, null);
            } else if (path.startsWith("/api/entries/") && method.equals("PUT")) {
                saveEntry(ex, URLDecoder.decode(path.substring("/api/entries/".length()), StandardCharsets.UTF_8));
            } else if (path.startsWith("/api/entries/") && method.equals("DELETE")) {
                deleteEntry(ex, URLDecoder.decode(path.substring("/api/entries/".length()), StandardCharsets.UTF_8));
            } else if (path.equals("/api/tournament") && method.equals("GET")) {
                sendJson(ex, 200, status());
            } else if (path.equals("/api/tournament") && method.equals("POST")) {
                startTournament(ex);
            } else if (path.equals("/api/tournament/stop") && method.equals("POST")) {
                stopTournament(ex);
            } else if (path.equals("/api/history") && method.equals("GET")) {
                sendJson(ex, 200, listHistory());
            } else if (path.matches("/api/history/[^/]+/games/[0-9]+/replay") && method.equals("GET")) {
                String[] parts = path.split("/");
                replayGame(ex, parts[3], Integer.parseInt(parts[5]));
            } else if (path.matches("/api/history/[^/]+/trees/[0-9]+") && method.equals("GET")) {
                String[] parts = path.split("/");
                getTree(ex, parts[3], Integer.parseInt(parts[5]));
            } else if (path.startsWith("/api/history/") && method.equals("GET")) {
                getHistory(ex, path.substring("/api/history/".length()));
            } else if (path.startsWith("/api/history/") && method.equals("DELETE")) {
                deleteHistory(ex, path.substring("/api/history/".length()));
            } else {
                sendJson(ex, 404, Map.of("error", "Not found: " + method + " " + path));
            }
        } catch (RuntimeException e) {
            sendJson(ex, 500, Map.of("error", String.valueOf(e.getMessage())));
        } finally {
            ex.close();
        }
    }

    /** Exact match first, then names with a word starting with the query, then other substrings. */
    private List<String> searchCards(String q) {
        String needle = q.strip().toLowerCase(Locale.ROOT);
        if (needle.length() < 2) {
            return List.of();
        }
        List<String> matches = new ArrayList<>();
        for (String name : cardNames) {
            if (name.toLowerCase(Locale.ROOT).contains(needle)) {
                matches.add(name);
            }
        }
        matches.sort(Comparator.<String>comparingInt(n -> rank(n, needle))
                .thenComparingInt(String::length)
                .thenComparing(String.CASE_INSENSITIVE_ORDER));
        return matches.subList(0, Math.min(12, matches.size()));
    }

    private static int rank(String name, String needle) {
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.equals(needle)) {
            return 0;
        }
        if (lower.startsWith(needle) || lower.contains(" " + needle) || lower.contains("-" + needle)) {
            return 1;
        }
        return 2;
    }

    private List<Map<String, Object>> listEntries() throws IOException {
        List<Map<String, Object>> out = new ArrayList<>();
        try (Stream<Path> files = Files.list(entriesDir)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".txt")).sorted().toList()) {
                EntryFile entry = EntryFile.read(f);
                DeckList deck = entry.deck();
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("name", deck.name());
                m.put("cards", deck.cards());
                m.put("problems", VintageValidator.problems(deck));
                m.put("created", entry.created() == null ? null : entry.created().toString());
                m.put("updated", entry.updated().toString());
                m.put("notes", entry.notes());
                out.add(m);
            }
        }
        return out;
    }

    private List<DeckList> readEntries() throws IOException {
        List<DeckList> decks = new ArrayList<>();
        try (Stream<Path> files = Files.list(entriesDir)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".txt")).sorted().toList()) {
                decks.add(DeckList.read(f));
            }
        }
        return decks;
    }

    /**
     * Adds an entry ({@code original} null) or changes the entry called {@code original}: its
     * cards, notes, and possibly its name. The body has {@code name}, {@code cards} and
     * {@code notes}. A changed entry keeps its created date.
     */
    private void saveEntry(HttpExchange ex, String original) throws IOException {
        JsonObject body = GSON.fromJson(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8), JsonObject.class);
        String name = body != null && body.has("name") ? body.get("name").getAsString().strip() : "";
        List<String> cards = new ArrayList<>();
        if (body != null && body.has("cards")) {
            body.getAsJsonArray("cards").forEach(c -> cards.add(c.getAsString().strip()));
        }
        String notes = body != null && body.has("notes") && !body.get("notes").isJsonNull() ? body.get("notes").getAsString().strip() : "";
        Path old = original == null ? null : entryFile(original);
        if (old != null && (!validName(original) || !Files.exists(old))) {
            sendJson(ex, 404, Map.of("problems", List.of("There's no entry called " + original + " any more.")));
            return;
        }
        if (!validName(name)) {
            sendJson(ex, 400, Map.of("problems", List.of("Name the entry with letters, digits, spaces, - _ or ' (up to " + MAX_NAME + " characters).")));
            return;
        }
        if (notes.length() > MAX_NOTES) {
            sendJson(ex, 400, Map.of("problems", List.of("Keep the notes under " + MAX_NOTES + " characters.")));
            return;
        }
        DeckList deck = new DeckList(name, cards);
        List<String> problems = VintageValidator.problems(deck);
        if (!problems.isEmpty()) {
            sendJson(ex, 400, Map.of("problems", problems));
            return;
        }
        Path file = entryFile(name);
        if (Files.exists(file) && !name.equals(original)) {
            sendJson(ex, 409, Map.of("problems", List.of("There's already an entry called " + name + ". Pick another name or remove it first.")));
            return;
        }
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        Instant created = old == null ? now : EntryFile.read(old).created();
        new EntryFile(deck, created, now, notes).write(file);
        if (old != null && !old.equals(file)) {
            Files.delete(old);
        }
        sendJson(ex, original == null ? 201 : 200, Map.of("name", name, "cards", cards));
    }

    /**
     * Imports entries from text (see EntryImport). The body has {@code text}, {@code apply} (false
     * only checks) and {@code replace} (overwrite entries with the same name; otherwise they're
     * skipped). Each entry is checked and imported on its own: one bad block doesn't stop the rest.
     */
    private void importEntries(HttpExchange ex) throws IOException {
        JsonObject body = GSON.fromJson(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8), JsonObject.class);
        String text = body != null && body.has("text") ? body.get("text").getAsString() : "";
        boolean apply = body != null && body.has("apply") && body.get("apply").getAsBoolean();
        boolean replace = body != null && body.has("replace") && body.get("replace").getAsBoolean();
        List<Map<String, Object>> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        int imported = 0;
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        for (EntryImport.Block b : EntryImport.parse(text)) {
            List<String> problems = new ArrayList<>(b.problems());
            if (!validName(b.name())) {
                problems.add("The name has to be letters, digits, spaces, - _ or ' (up to " + MAX_NAME + " characters).");
            }
            if (!seen.add(b.name())) {
                problems.add("Another entry in this import has the same name.");
            }
            if (b.notes().length() > MAX_NOTES) {
                problems.add("The notes are over " + MAX_NOTES + " characters.");
            }
            DeckList deck = new DeckList(b.name(), b.cards());
            if (problems.isEmpty()) {
                problems.addAll(VintageValidator.problems(deck));
            }
            boolean exists = validName(b.name()) && Files.exists(entryFile(b.name()));
            String result = !problems.isEmpty() ? "invalid" : exists && !replace ? "skipped" : exists ? "replaced" : "added";
            if (apply && problems.isEmpty() && (!exists || replace)) {
                Instant created = exists ? EntryFile.read(entryFile(b.name())).created() : now;
                new EntryFile(deck, created, now, b.notes()).write(entryFile(b.name()));
                imported++;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", b.name());
            m.put("cards", b.cards());
            m.put("notes", b.notes());
            m.put("problems", problems);
            m.put("exists", exists);
            m.put("result", result);
            out.add(m);
        }
        sendJson(ex, 200, Map.of("entries", out, "imported", imported, "applied", apply));
    }

    /** Every entry in the import format, as a file to save. */
    private void exportEntries(HttpExchange ex) throws IOException {
        List<EntryFile> entries = new ArrayList<>();
        try (Stream<Path> files = Files.list(entriesDir)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".txt")).sorted().toList()) {
                entries.add(EntryFile.read(f));
            }
        }
        byte[] bytes = EntryImport.export(entries).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        ex.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"3cb-entries.txt\"");
        ex.sendResponseHeaders(200, bytes.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static boolean validName(String name) {
        return name.matches("[A-Za-z0-9][A-Za-z0-9 _'-]*") && name.length() <= MAX_NAME;
    }

    private void deleteEntry(HttpExchange ex, String name) throws IOException {
        Path file = entryFile(name);
        if (!file.normalize().startsWith(entriesDir.normalize()) || !Files.deleteIfExists(file)) {
            sendJson(ex, 404, Map.of("error", "No entry called " + name));
            return;
        }
        sendJson(ex, 200, Map.of("removed", name));
    }

    private Path entryFile(String name) {
        return entriesDir.resolve(name + ".txt");
    }

    private synchronized Map<String, Object> status() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("state", state);
        m.put("progress", progress);
        m.put("running", runningGames());
        m.put("totalGames", totalGames);
        m.put("options", optionsJson(options, saveTrees));
        m.put("defaults", optionsJson(DEFAULTS, false));
        m.put("results", results);
        m.put("error", error);
        return m;
    }

    /** The search settings the page can change, in the form it sends them back. */
    private static Map<String, Object> optionsJson(Solver.Options o, boolean trees) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("timeSeconds", o.timeBudget().toSeconds());
        m.put("nodes", o.nodeBudget());
        m.put("turns", o.maxTurns() / 2); // turn cycles; the solver counts each player's turn
        m.put("exactTurns", o.exactTurns());
        m.put("saveTrees", trees);
        return m;
    }

    /** The body's {@code options.saveTrees}: record every game's search tree. */
    private static boolean parseSaveTrees(JsonObject body) {
        if (body == null || !body.has("options") || !body.get("options").isJsonObject()) {
            return false;
        }
        JsonObject o = body.getAsJsonObject("options");
        return o.has("saveTrees") && o.get("saveTrees").isJsonPrimitive() && o.get("saveTrees").getAsBoolean();
    }

    /**
     * Reads the body's {@code options} object; missing settings keep their defaults. Out-of-range
     * values add to {@code problems}.
     */
    private static Solver.Options parseOptions(JsonObject body, List<String> problems) {
        if (body == null || !body.has("options") || !body.get("options").isJsonObject()) {
            return DEFAULTS;
        }
        JsonObject o = body.getAsJsonObject("options");
        long time = DEFAULTS.timeBudget().toSeconds();
        long nodes = DEFAULTS.nodeBudget();
        int turns = DEFAULTS.maxTurns() / 2;
        boolean exactTurns = DEFAULTS.exactTurns();
        try {
            if (o.has("timeSeconds")) {
                time = o.get("timeSeconds").getAsLong();
            }
            if (o.has("nodes")) {
                nodes = o.get("nodes").getAsLong();
            }
            if (o.has("turns")) {
                turns = o.get("turns").getAsInt();
            }
            if (o.has("exactTurns")) {
                exactTurns = o.get("exactTurns").getAsBoolean();
            }
        } catch (RuntimeException e) {
            problems.add("Search settings must be whole numbers.");
            return DEFAULTS;
        }
        if (time < 1 || time > MAX_TIME_SECONDS) {
            problems.add("Time per game must be between 1 second and 24 hours.");
        }
        if (nodes < MIN_NODES || nodes > MAX_NODES) {
            problems.add(String.format("Node budget must be between %,d and %,d.", MIN_NODES, MAX_NODES));
        }
        if (turns < 1 || turns > MAX_TURNS) {
            problems.add("Turn limit must be between 1 and " + MAX_TURNS + ".");
        }
        int limit = Turns.forgeTurns(turns);
        return new Solver.Options(limit, limit, nodes, Duration.ofSeconds(time), exactTurns);
    }

    /** Live statistics of the games being solved, longest-running first. */
    private List<Map<String, Object>> runningGames() {
        List<Map<String, Object>> out = new ArrayList<>();
        List<DeckList> entries = tournamentEntries;
        for (Tournament.Running r : running) {
            Solver.Progress p = r.solver().progress();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("a", entries.get(r.a()).name());
            m.put("b", entries.get(r.b()).name());
            m.put("onThePlay", entries.get(r.startingPlayer() == 0 ? r.a() : r.b()).name());
            m.put("nodes", p.nodes());
            m.put("seconds", p.elapsed().toMillis() / 1000.0);
            m.put("turnLimit", p.turnLimit() / 2);
            m.put("maxTurns", options.maxTurns() / 2);
            m.put("pass", p.pass());
            m.put("budgetUsed", p.budgetUsed());
            m.put("timeBudgetSeconds", options.timeBudget().toSeconds());
            List<Map<String, Object>> line = new ArrayList<>();
            for (Solver.Decision d : p.line()) {
                Map<String, Object> dm = new LinkedHashMap<>();
                dm.put("player", d.player() < 0 ? null : entries.get(d.player() == 0 ? r.a() : r.b()).name());
                dm.put("kind", d.kind());
                dm.put("turn", d.turn());
                dm.put("choice", d.choice());
                dm.put("tried", d.tried());
                dm.put("options", d.options());
                line.add(dm);
            }
            m.put("line", line);
            m.put("depth", p.depth());
            out.add(m);
        }
        out.sort(Comparator.comparingDouble((Map<String, Object> m) -> (double) m.get("seconds")).reversed());
        return out;
    }

    /**
     * Runs every entry, or only those named in the body's {@code entries} array, with the search
     * settings in its {@code options} object (see {@link #parseOptions}).
     */
    private void startTournament(HttpExchange ex) throws IOException {
        List<DeckList> all = readEntries();
        List<DeckList> entries = all;
        List<String> problems = new ArrayList<>();
        JsonObject body = GSON.fromJson(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8), JsonObject.class);
        if (body != null && body.has("entries")) {
            Set<String> chosen = new TreeSet<>();
            body.getAsJsonArray("entries").forEach(n -> chosen.add(n.getAsString()));
            for (String name : chosen) {
                if (all.stream().noneMatch(d -> d.name().equals(name))) {
                    problems.add("No entry called " + name + ".");
                }
            }
            entries = all.stream().filter(d -> chosen.contains(d.name())).toList();
        }
        for (DeckList d : entries) {
            for (String p : VintageValidator.problems(d)) {
                problems.add(d.name() + ": " + p);
            }
        }
        if (entries.size() < 2) {
            problems.add("Choose at least two entries to run a tournament.");
        }
        Solver.Options chosenOptions = parseOptions(body, problems);
        boolean trees = parseSaveTrees(body);
        if (trees && entries.size() > MAX_TREE_ENTRIES) {
            problems.add("Saving search trees is for small tournaments: choose at most " + MAX_TREE_ENTRIES
                    + " entries, or untick it in the search settings.");
        }
        if (!problems.isEmpty()) {
            sendJson(ex, 400, Map.of("problems", problems));
            return;
        }
        List<DeckList> field = entries;
        synchronized (this) {
            if (state.equals("running") || state.equals("stopping")) {
                sendJson(ex, 409, Map.of("problems", List.of("A tournament is already running.")));
                return;
            }
            state = "running";
            progress.clear();
            running.clear();
            tournamentEntries = field;
            options = chosenOptions;
            saveTrees = trees;
            stop = new Tournament.Stop();
            totalGames = field.size() * (field.size() - 1);
            results = null;
            error = null;
        }
        Tournament.Stop thisStop = stop;
        Thread runner = new Thread(() -> {
            try {
                long start = System.nanoTime();
                Tournament.Results r = Tournament.run(field, chosenOptions, threads, progress::add, running, thisStop, cache, trees);
                Map<String, Object> json = ResultsJson.toJson(r);
                boolean stopped = !r.notPlayed().isEmpty();
                // A tournament stopped before any game finished has nothing worth keeping.
                if (!r.games().isEmpty()) {
                    save(json, r, chosenOptions, trees, Duration.ofNanos(System.nanoTime() - start));
                }
                synchronized (this) {
                    results = json;
                    state = stopped ? "stopped" : "done";
                }
            } catch (IOException e) {
                synchronized (this) {
                    error = "Solved, but couldn't save the results: " + e.getMessage();
                    state = "failed";
                }
            } catch (RuntimeException e) {
                synchronized (this) {
                    error = String.valueOf(e.getMessage());
                    state = "failed";
                }
            }
        }, "tcb-tournament");
        runner.setDaemon(true);
        runner.start();
        sendJson(ex, 202, status());
    }

    /** Stops the running tournament: finished games are kept, the rest are marked not played. */
    private void stopTournament(HttpExchange ex) throws IOException {
        synchronized (this) {
            if (state.equals("running")) {
                state = "stopping";
                stop.request();
            }
        }
        sendJson(ex, 200, status());
    }

    // ---- saved tournaments

    /**
     * Writes a finished tournament to the history directory, named by when it finished. Search
     * trees, if saved, go in a folder beside it: {@code <id>.trees/<k>.json.gz} for the k-th game
     * of {@code results.games}.
     */
    private synchronized void save(Map<String, Object> results, Tournament.Results r, Solver.Options options,
                                   boolean trees, Duration elapsed) throws IOException {
        String base = LocalDateTime.now().format(ID_FORMAT);
        String id = base;
        for (int n = 2; Files.exists(historyFile(id)); n++) {
            id = base + "-" + n;
        }
        if (trees) {
            Files.createDirectories(treesDir(id));
            for (int k = 0; k < r.games().size(); k++) {
                byte[] tree = r.games().get(k).tree();
                if (tree != null) {
                    Files.write(treeFile(id, k), tree);
                }
            }
        }
        Map<String, Object> saved = new LinkedHashMap<>();
        saved.put("id", id);
        saved.put("finished", LocalDateTime.now().withNano(0).toString());
        saved.put("seconds", elapsed.toMillis() / 1000.0);
        saved.put("options", optionsJson(options, trees));
        saved.put("results", results);
        Files.writeString(historyFile(id), GSON.toJson(saved));
    }

    /** Summaries of the saved tournaments, newest first. */
    private List<Map<String, Object>> listHistory() throws IOException {
        List<Map<String, Object>> out = new ArrayList<>();
        try (Stream<Path> files = Files.list(historyDir)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".json")).sorted(Comparator.reverseOrder()).toList()) {
                JsonObject saved = GSON.fromJson(Files.readString(f), JsonObject.class);
                JsonObject results = saved.getAsJsonObject("results");
                List<String> entries = new ArrayList<>();
                results.getAsJsonArray("entries").forEach(e -> entries.add(e.getAsJsonObject().get("name").getAsString()));
                JsonObject top = results.getAsJsonArray("standings").get(0).getAsJsonObject();
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", saved.get("id").getAsString());
                m.put("finished", saved.get("finished").getAsString());
                m.put("seconds", saved.get("seconds").getAsDouble());
                m.put("entries", entries);
                m.put("games", results.getAsJsonArray("games").size());
                m.put("notPlayed", results.has("notPlayed") ? results.getAsJsonArray("notPlayed").size() : 0);
                m.put("leader", top.get("name").getAsString());
                m.put("leaderPoints", top.get("points").getAsDouble());
                out.add(m);
            }
        }
        return out;
    }

    private void getHistory(HttpExchange ex, String id) throws IOException {
        if (!validId(id) || !Files.exists(historyFile(id))) {
            sendJson(ex, 404, Map.of("error", "No saved tournament " + id));
            return;
        }
        sendJson(ex, 200, GSON.fromJson(Files.readString(historyFile(id)), JsonObject.class));
    }

    private void deleteHistory(HttpExchange ex, String id) throws IOException {
        if (!validId(id) || !Files.deleteIfExists(historyFile(id))) {
            sendJson(ex, 404, Map.of("error", "No saved tournament " + id));
            return;
        }
        if (Files.isDirectory(treesDir(id))) {
            try (Stream<Path> files = Files.list(treesDir(id))) {
                for (Path f : files.toList()) {
                    Files.delete(f);
                }
            }
            Files.delete(treesDir(id));
        }
        sendJson(ex, 200, Map.of("removed", id));
    }

    /**
     * The board at every step of a saved game's optimal line, for the animated replay: the game
     * is replayed in Forge along the saved line (see GameReplay).
     */
    private void replayGame(HttpExchange ex, String id, int game) throws IOException {
        if (!validId(id) || !Files.exists(historyFile(id))) {
            sendJson(ex, 404, Map.of("error", "No saved tournament " + id));
            return;
        }
        JsonObject saved = GSON.fromJson(Files.readString(historyFile(id)), JsonObject.class);
        JsonObject results = saved.getAsJsonObject("results");
        if (game >= results.getAsJsonArray("games").size()) {
            sendJson(ex, 404, Map.of("error", "No game " + game + " in that tournament."));
            return;
        }
        JsonObject g = results.getAsJsonArray("games").get(game).getAsJsonObject();
        DeckList a = savedDeck(results, g.get("a").getAsString());
        DeckList b = savedDeck(results, g.get("b").getAsString());
        int startingPlayer = g.get("onThePlay").getAsString().equals(a.name()) ? 0 : 1;
        List<String> line = new ArrayList<>();
        g.getAsJsonArray("line").forEach(l -> line.add(l.getAsString()));
        // Older saves have maxTurns (Forge turns); newer ones turns (turn cycles).
        JsonObject o = saved.has("options") ? saved.getAsJsonObject("options") : new JsonObject();
        int turnLimit = o.has("turns") ? Turns.forgeTurns(o.get("turns").getAsInt())
                : o.has("maxTurns") ? o.get("maxTurns").getAsInt() : DEFAULTS.maxTurns();
        try {
            sendJson(ex, 200, GameReplay.replay(a, b, startingPlayer, line, turnLimit));
        } catch (RuntimeException e) {
            if (!NondeterministicReplayException.causedBy(e)) {
                throw e;
            }
            sendJson(ex, 409, Map.of("error", "This game can't be replayed: the engine no longer plays its saved line"
                    + " the same way (the solver has changed since it was saved). " + e.getMessage()));
        }
    }

    private static DeckList savedDeck(JsonObject results, String name) {
        for (var e : results.getAsJsonArray("entries")) {
            JsonObject entry = e.getAsJsonObject();
            if (entry.get("name").getAsString().equals(name)) {
                List<String> cards = new ArrayList<>();
                entry.getAsJsonArray("cards").forEach(c -> cards.add(c.getAsString()));
                return new DeckList(name, cards);
            }
        }
        throw new IllegalStateException("saved tournament has no entry called " + name);
    }

    /** One game's saved search tree, sent as stored: gzipped JSON (see SearchTree#toGzipJson). */
    private void getTree(HttpExchange ex, String id, int game) throws IOException {
        if (!validId(id) || !Files.exists(treeFile(id, game))) {
            sendJson(ex, 404, Map.of("error", "No search tree saved for that game."));
            return;
        }
        byte[] bytes = Files.readAllBytes(treeFile(id, game));
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.getResponseHeaders().set("Content-Encoding", "gzip");
        ex.sendResponseHeaders(200, bytes.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static boolean validId(String id) {
        return id.matches("[0-9]{8}-[0-9]{6}(-[0-9]+)?");
    }

    private Path historyFile(String id) {
        return historyDir.resolve(id + ".json");
    }

    private Path treesDir(String id) {
        return historyDir.resolve(id + ".trees");
    }

    private Path treeFile(String id, int game) {
        return treesDir(id).resolve(game + ".json.gz");
    }

    private static Map<String, String> query(HttpExchange ex) {
        Map<String, String> q = new LinkedHashMap<>();
        String raw = ex.getRequestURI().getRawQuery();
        if (raw == null) {
            return q;
        }
        for (String pair : raw.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                q.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                        URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
            }
        }
        return q;
    }

    private static void sendJson(HttpExchange ex, int status, Object body) throws IOException {
        byte[] bytes = GSON.toJson(body).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static void sendResource(HttpExchange ex, String resource, String type) throws IOException {
        try (InputStream in = WebServer.class.getResourceAsStream(resource)) {
            if (in == null) {
                sendJson(ex, 404, Map.of("error", "missing " + resource));
                return;
            }
            byte[] bytes = in.readAllBytes();
            ex.getResponseHeaders().set("Content-Type", type);
            ex.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = ex.getResponseBody()) {
                out.write(bytes);
            }
        }
    }
}
