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
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
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
import tcb.deck.VintageValidator;
import tcb.engine.ForgeEnv;
import tcb.solve.ResultCache;
import tcb.solve.ResultsJson;
import tcb.solve.Solver;
import tcb.solve.Tournament;

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
    private static final Solver.Options DEFAULTS = Solver.Options.defaults();
    private static final int MAX_TIME_SECONDS = 24 * 60 * 60;
    private static final long MIN_NODES = 1_000, MAX_NODES = 1_000_000_000;
    private static final int MAX_TURNS = 200;
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
            } else if (path.equals("/api/entries") && method.equals("POST")) {
                addEntry(ex);
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
        for (DeckList deck : readEntries()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", deck.name());
            m.put("cards", deck.cards());
            m.put("problems", VintageValidator.problems(deck));
            out.add(m);
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

    private void addEntry(HttpExchange ex) throws IOException {
        JsonObject body = GSON.fromJson(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8), JsonObject.class);
        String name = body != null && body.has("name") ? body.get("name").getAsString().strip() : "";
        List<String> cards = new ArrayList<>();
        if (body != null && body.has("cards")) {
            body.getAsJsonArray("cards").forEach(c -> cards.add(c.getAsString().strip()));
        }
        if (!name.matches("[A-Za-z0-9][A-Za-z0-9 _'-]*") || name.length() > MAX_NAME) {
            sendJson(ex, 400, Map.of("problems", List.of("Name the entry with letters, digits, spaces, - _ or ' (up to " + MAX_NAME + " characters).")));
            return;
        }
        DeckList deck = new DeckList(name, cards);
        List<String> problems = VintageValidator.problems(deck);
        if (!problems.isEmpty()) {
            sendJson(ex, 400, Map.of("problems", problems));
            return;
        }
        Path file = entryFile(name);
        if (Files.exists(file)) {
            sendJson(ex, 409, Map.of("problems", List.of("There's already an entry called " + name + ". Pick another name or remove it first.")));
            return;
        }
        Files.writeString(file, String.join("\n", cards) + "\n");
        sendJson(ex, 201, Map.of("name", name, "cards", cards));
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
        m.put("options", optionsJson(options));
        m.put("defaults", optionsJson(DEFAULTS));
        m.put("results", results);
        m.put("error", error);
        return m;
    }

    /** The search settings the page can change, in the form it sends them back. */
    private static Map<String, Object> optionsJson(Solver.Options o) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("timeSeconds", o.timeBudget().toSeconds());
        m.put("nodes", o.nodeBudget());
        m.put("maxTurns", o.maxTurns());
        m.put("exactTurns", o.exactTurns());
        return m;
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
        int maxTurns = DEFAULTS.maxTurns();
        boolean exactTurns = DEFAULTS.exactTurns();
        try {
            if (o.has("timeSeconds")) {
                time = o.get("timeSeconds").getAsLong();
            }
            if (o.has("nodes")) {
                nodes = o.get("nodes").getAsLong();
            }
            if (o.has("maxTurns")) {
                maxTurns = o.get("maxTurns").getAsInt();
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
        if (maxTurns < 1 || maxTurns > MAX_TURNS) {
            problems.add("Turn limit must be between 1 and " + MAX_TURNS + ".");
        }
        return new Solver.Options(maxTurns, maxTurns, nodes, Duration.ofSeconds(time), exactTurns);
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
            m.put("turnLimit", p.turnLimit());
            m.put("maxTurns", options.maxTurns());
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
            stop = new Tournament.Stop();
            totalGames = field.size() * (field.size() - 1);
            results = null;
            error = null;
        }
        Tournament.Stop thisStop = stop;
        Thread runner = new Thread(() -> {
            try {
                long start = System.nanoTime();
                Tournament.Results r = Tournament.run(field, chosenOptions, threads, progress::add, running, thisStop, cache);
                Map<String, Object> json = ResultsJson.toJson(r);
                boolean stopped = !r.notPlayed().isEmpty();
                // A tournament stopped before any game finished has nothing worth keeping.
                if (!r.games().isEmpty()) {
                    save(json, chosenOptions, Duration.ofNanos(System.nanoTime() - start));
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

    /** Writes a finished tournament to the history directory, named by when it finished. */
    private synchronized void save(Map<String, Object> results, Solver.Options options, Duration elapsed) throws IOException {
        String base = LocalDateTime.now().format(ID_FORMAT);
        String id = base;
        for (int n = 2; Files.exists(historyFile(id)); n++) {
            id = base + "-" + n;
        }
        Map<String, Object> saved = new LinkedHashMap<>();
        saved.put("id", id);
        saved.put("finished", LocalDateTime.now().withNano(0).toString());
        saved.put("seconds", elapsed.toMillis() / 1000.0);
        saved.put("options", optionsJson(options));
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
        sendJson(ex, 200, Map.of("removed", id));
    }

    private static boolean validId(String id) {
        return id.matches("[0-9]{8}-[0-9]{6}(-[0-9]+)?");
    }

    private Path historyFile(String id) {
        return historyDir.resolve(id + ".json");
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
