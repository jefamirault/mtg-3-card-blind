package tcb.cli;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.stream.Stream;

import com.google.gson.GsonBuilder;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import tcb.deck.DeckList;
import tcb.deck.VintageValidator;
import tcb.engine.ForgeEnv;
import tcb.solve.Solver;
import tcb.solve.Solver.GameResult;
import tcb.solve.Tournament;
import tcb.solve.ResultsJson;
import tcb.solve.Tournament.Results;
import tcb.solve.Tournament.Standing;

@Command(name = "tcb", mixinStandardHelpOptions = true, version = "3 Card Blind 0.1.0",
        description = "3 Card Blind: MtG Edition. Decks of 3 Vintage-legal cards, judged by solving each game under optimal play.",
        subcommands = {Main.Validate.class, Main.Solve.class, Main.TournamentCmd.class})
public final class Main {
    /** Real stdout; Forge is chatty, so its System.out goes to stderr while we run. */
    static final PrintStream OUT = System.out;

    public static void main(String[] args) {
        System.setOut(System.err);
        int code = new CommandLine(new Main()).execute(args);
        System.exit(code);
    }

    /** Search limits shared by solve and tournament (a picocli mixin, so public with a no-arg constructor). */
    public static class SearchOptions {
        @Option(names = "--max-turns", defaultValue = "50", description = "Turn limit (counting both players' turns); unfinished games are draws. Default: ${DEFAULT-VALUE}")
        int maxTurns;

        @Option(names = "--first-turns", description = "Iteratively deepen the turn limit from this value (doubling), so a budget overrun still leaves a result proven to a shorter limit. Default: off")
        Integer firstTurns;

        @Option(names = "--exact-turns", description = "Make the transposition table turn-aware: slower, but exact for games decided near the turn limit.")
        boolean exactTurns;

        @Option(names = "--nodes", defaultValue = "2000000", description = "Max search nodes per game. Default: ${DEFAULT-VALUE}")
        long nodes;

        @Option(names = "--time", defaultValue = "10m", description = "Max time per game, e.g. 90s, 10m. Default: ${DEFAULT-VALUE}")
        String time;

        Solver.Options toOptions() {
            return new Solver.Options(maxTurns, firstTurns == null ? maxTurns : firstTurns, nodes, parseDuration(time), exactTurns);
        }
    }

    static Duration parseDuration(String s) {
        String t = s.strip().toLowerCase();
        long n = Long.parseLong(t.replaceAll("[a-z]+$", ""));
        if (t.endsWith("ms")) return Duration.ofMillis(n);
        if (t.endsWith("s")) return Duration.ofSeconds(n);
        if (t.endsWith("m")) return Duration.ofMinutes(n);
        if (t.endsWith("h")) return Duration.ofHours(n);
        return Duration.ofSeconds(n);
    }

    static List<Path> deckFiles(List<Path> paths) throws IOException {
        List<Path> files = new ArrayList<>();
        for (Path p : paths) {
            if (Files.isDirectory(p)) {
                try (Stream<Path> s = Files.list(p)) {
                    s.filter(f -> f.toString().endsWith(".txt")).sorted().forEach(files::add);
                }
            } else {
                files.add(p);
            }
        }
        return files;
    }

    @Command(name = "validate", description = "Check deck files (or directories of .txt decks) for 3CB/Vintage legality.")
    static class Validate implements Callable<Integer> {
        @Parameters(arity = "1..*", paramLabel = "DECK")
        List<Path> paths;

        @Override
        public Integer call() throws IOException {
            ForgeEnv.init();
            int bad = 0;
            for (Path file : deckFiles(paths)) {
                DeckList deck = DeckList.read(file);
                List<String> problems = VintageValidator.problems(deck);
                if (problems.isEmpty()) {
                    OUT.printf("OK    %s: %s%n", deck.name(), String.join(", ", deck.cards()));
                } else {
                    bad++;
                    OUT.printf("ERROR %s: %s%n", deck.name(), String.join("; ", problems));
                }
            }
            return bad == 0 ? 0 : 1;
        }
    }

    @Command(name = "solve", description = "Solve one pairing: both games (each deck on the play once), with the optimal lines.")
    static class Solve implements Callable<Integer> {
        @Parameters(index = "0", paramLabel = "DECK_A")
        Path deckA;

        @Parameters(index = "1", paramLabel = "DECK_B")
        Path deckB;

        @CommandLine.Mixin
        SearchOptions search;

        @Override
        public Integer call() throws IOException {
            ForgeEnv.init();
            DeckList a = DeckList.read(deckA);
            DeckList b = DeckList.read(deckB);
            VintageValidator.requireLegal(a);
            VintageValidator.requireLegal(b);
            OUT.printf("A = %s: %s%nB = %s: %s%n", a.name(), String.join(", ", a.cards()), b.name(), String.join(", ", b.cards()));
            for (int start = 0; start < 2; start++) {
                GameResult r = new Solver(a, b, start, search.toOptions()).solve();
                OUT.printf("%n%s on the play: %s%s  [%d nodes, %.1fs]%n", start == 0 ? "A" : "B", r.describe(),
                        r.complete() ? "" : " (unproven beyond turn " + r.provenTurnLimit() + ")",
                        r.nodes(), r.elapsed().toMillis() / 1000.0);
                r.principalVariation().forEach(line -> OUT.println("    " + line));
            }
            return 0;
        }
    }

    @Command(name = "tournament", description = "Run a 3CB round robin over deck files and print the matchup matrix and standings.")
    static class TournamentCmd implements Callable<Integer> {
        @Parameters(arity = "1..*", paramLabel = "DECKS", description = "Deck files or directories of .txt decks")
        List<Path> paths;

        @Option(names = "--threads", description = "Parallel games. Default: number of CPUs")
        int threads = Runtime.getRuntime().availableProcessors();

        @Option(names = "--json", description = "Also write full results (including optimal lines) to this file")
        Path json;

        @CommandLine.Mixin
        SearchOptions search;

        @Override
        public Integer call() throws IOException {
            ForgeEnv.init();
            List<DeckList> entries = new ArrayList<>();
            for (Path file : deckFiles(paths)) {
                entries.add(DeckList.read(file));
            }
            if (entries.size() < 2) {
                OUT.println("Need at least two decks.");
                return 1;
            }
            Results results = Tournament.run(entries, search.toOptions(), threads, msg -> OUT.println("  " + msg));
            printMatrix(results);
            printStandings(results);
            if (json != null) {
                Files.writeString(json, new GsonBuilder().setPrettyPrinting().create().toJson(ResultsJson.toJson(results)));
                OUT.println("\nWrote " + json);
            }
            return 0;
        }
    }

    static void printMatrix(Results r) {
        int n = r.entries().size();
        int w = Math.max(6, r.entries().stream().mapToInt(d -> d.name().length()).max().orElse(6));
        OUT.println("\nPoints scored by row deck against column deck (2 games, 3 per win, 1 per draw):");
        StringBuilder header = new StringBuilder(" ".repeat(w + 2));
        for (int j = 0; j < n; j++) {
            header.append(String.format("%6d", j + 1));
        }
        OUT.println(header);
        for (int i = 0; i < n; i++) {
            StringBuilder row = new StringBuilder(String.format("%-" + w + "s%2d", r.entries().get(i).name(), i + 1));
            for (int j = 0; j < n; j++) {
                row.append(i == j ? "     -" : String.format("%6s", fmt(r.pointsAgainst(i, j))));
            }
            OUT.println(row);
        }
    }

    static void printStandings(Results r) {
        OUT.println("\nStandings:");
        int rank = 1;
        for (Standing s : r.standings()) {
            OUT.printf("%2d. %-24s %6s pts  (W-D-L %s-%s-%s)  %s%n", rank++, s.deck().name(), fmt(s.points()),
                    fmt(s.wins()), fmt(s.draws()), fmt(s.losses()), String.join(", ", s.deck().cards()));
        }
        if (!r.allComplete()) {
            OUT.println("\nNote: some games hit the search budget; their results are only proven up to the reported turn limit.");
        }
    }

    /** Whole numbers without decimals, otherwise one decimal place. */
    static String fmt(double x) {
        return Math.abs(x - Math.rint(x)) < 1e-9 ? String.valueOf((long) Math.rint(x)) : String.format("%.1f", x);
    }

}
