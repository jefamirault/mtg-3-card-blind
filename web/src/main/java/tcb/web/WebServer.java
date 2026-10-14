package tcb.web;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
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
import tcb.solve.ResultsJson;
import tcb.solve.Solver;
import tcb.solve.Tournament;

/**
 * Web front end: submit entries, run the tournament, browse results.
 * Entries are stored as deck files in a directory, so the CLI can use the same folder.
 *
 * <pre>java ... tcb.web.WebServer [--port 8080] [--entries entries] [--threads N]</pre>
 */
public final class WebServer {
    private static final Gson GSON = new GsonBuilder().serializeNulls().create();
    private static final int MAX_NAME = 40;

    private final Path entriesDir;
    private final int threads;
    private final List<String> cardNames;

    // tournament state, guarded by this
    private String state = "idle"; // idle | running | done | failed
    private final List<String> progress = new CopyOnWriteArrayList<>();
    private int totalGames;
    private Map<String, Object> results;
    private String error;

    WebServer(Path entriesDir, int threads) {
        this.entriesDir = entriesDir;
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
        int threads = Runtime.getRuntime().availableProcessors();
        for (int i = 0; i + 1 < args.length; i += 2) {
            switch (args[i]) {
                case "--port" -> port = Integer.parseInt(args[i + 1]);
                case "--entries" -> entries = Path.of(args[i + 1]);
                case "--threads" -> threads = Integer.parseInt(args[i + 1]);
                default -> throw new IllegalArgumentException("unknown option " + args[i]);
            }
        }
        Files.createDirectories(entries);
        ForgeEnv.init();
        WebServer app = new WebServer(entries, threads);
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/", app::handle);
        server.setExecutor(Executors.newFixedThreadPool(8));
        server.start();
        System.err.println("3 Card Blind: http://localhost:" + port + "/  (entries in " + entries.toAbsolutePath() + ")");
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
        m.put("totalGames", totalGames);
        m.put("results", results);
        m.put("error", error);
        return m;
    }

    private void startTournament(HttpExchange ex) throws IOException {
        List<DeckList> entries = readEntries();
        List<String> problems = new ArrayList<>();
        for (DeckList d : entries) {
            for (String p : VintageValidator.problems(d)) {
                problems.add(d.name() + ": " + p);
            }
        }
        if (entries.size() < 2) {
            problems.add("Add at least two entries to run a tournament.");
        }
        if (!problems.isEmpty()) {
            sendJson(ex, 400, Map.of("problems", problems));
            return;
        }
        synchronized (this) {
            if (state.equals("running")) {
                sendJson(ex, 409, Map.of("problems", List.of("A tournament is already running.")));
                return;
            }
            state = "running";
            progress.clear();
            totalGames = entries.size() * (entries.size() - 1);
            results = null;
            error = null;
        }
        Thread runner = new Thread(() -> {
            try {
                Tournament.Results r = Tournament.run(entries, Solver.Options.defaults(), threads, progress::add);
                synchronized (this) {
                    results = ResultsJson.toJson(r);
                    state = "done";
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
