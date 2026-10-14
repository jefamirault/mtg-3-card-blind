package tcb.solve;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Consumer;

import tcb.deck.DeckList;
import tcb.deck.VintageValidator;
import tcb.solve.Solver.GameResult;
import tcb.solve.Solver.Options;

/**
 * Round robin: every pair of entries plays two games, one with each deck on the play.
 * Scoring per game: win 3, draw 1, loss 0; games involving randomness score the expectation
 * (3 * P(win) + P(draw)). Highest total wins the tournament.
 */
public final class Tournament {
    public static final int WIN_POINTS = 3;
    public static final int DRAW_POINTS = 1;

    /** One solved game; {@code result.value()} is from deck {@code a}'s point of view. */
    public record Game(int a, int b, int startingPlayer, GameResult result) {
        /** Expected points for {@code entry} (which must be a or b). */
        public double pointsFor(int entry) {
            return WIN_POINTS * winChance(entry) + DRAW_POINTS * result.pDraw();
        }

        public double winChance(int entry) {
            return entry == a ? result.pWin() : result.pLoss();
        }

        public double lossChance(int entry) {
            return entry == a ? result.pLoss() : result.pWin();
        }
    }

    /** Wins/draws/losses are expected counts (fractional when games involve randomness). */
    public record Standing(DeckList deck, double points, double wins, double draws, double losses) {
    }

    public record Results(List<DeckList> entries, List<Game> games, List<Standing> standings) {
        /** Points entry {@code i} scored against entry {@code j} over both games. */
        public double pointsAgainst(int i, int j) {
            double total = 0;
            for (Game g : games) {
                if ((g.a() == i && g.b() == j) || (g.a() == j && g.b() == i)) {
                    total += g.pointsFor(i);
                }
            }
            return total;
        }

        public boolean allComplete() {
            return games.stream().allMatch(g -> g.result().complete());
        }
    }

    /** A game being solved right now; {@code solver.progress()} gives its live statistics. */
    public record Running(int a, int b, int startingPlayer, Solver solver) {
    }

    private Tournament() {
    }

    public static Results run(List<DeckList> entries, Options options, int threads, Consumer<String> progress) {
        return run(entries, options, threads, progress, ConcurrentHashMap.newKeySet());
    }

    /**
     * @param progress one line per finished game
     * @param running  games currently being solved: each is added when its search starts and
     *                 removed after its line has gone to {@code progress}. Must be thread-safe.
     */
    public static Results run(List<DeckList> entries, Options options, int threads, Consumer<String> progress,
                              Collection<Running> running) {
        for (DeckList deck : entries) {
            VintageValidator.requireLegal(deck);
        }
        ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, threads), r -> {
            // The search recurses once per decision; give solver threads room for long games.
            Thread t = new Thread(null, r, "tcb-solver", 64L * 1024 * 1024);
            t.setDaemon(true);
            return t;
        });
        try {
            List<Future<Game>> futures = new ArrayList<>();
            for (int i = 0; i < entries.size(); i++) {
                for (int j = i + 1; j < entries.size(); j++) {
                    for (int start = 0; start < 2; start++) {
                        final int a = i, b = j, s = start;
                        futures.add(pool.submit(() -> {
                            GameResult r;
                            Running live = null;
                            try {
                                Solver solver = new Solver(entries.get(a), entries.get(b), s, options);
                                live = new Running(a, b, s, solver);
                                running.add(live);
                                r = solver.solve();
                            } catch (RuntimeException e) {
                                // Don't lose the whole tournament to one game: score it as an unproven draw.
                                r = new GameResult(0, 1, 0, 0, false, List.of("solver error: " + e.getMessage()),
                                        0, java.time.Duration.ZERO);
                            }
                            Game game = new Game(a, b, s, r);
                            progress.accept(String.format("%s vs %s (%s on the play): %s%s",
                                    entries.get(a).name(), entries.get(b).name(),
                                    entries.get(s == 0 ? a : b).name(),
                                    r.describe().replace("A ", entries.get(a).name() + " ").replace("B ", entries.get(b).name() + " "),
                                    r.complete() ? "" : " (unproven beyond turn " + r.provenTurnLimit() + ")"));
                            if (live != null) {
                                running.remove(live);
                            }
                            return game;
                        }));
                    }
                }
            }
            List<Game> games = new ArrayList<>();
            for (Future<Game> f : futures) {
                games.add(f.get());
            }
            return new Results(List.copyOf(entries), games, standings(entries, games));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("solver failed: " + e.getCause(), e.getCause());
        } finally {
            pool.shutdownNow();
        }
    }

    static List<Standing> standings(List<DeckList> entries, List<Game> games) {
        List<Standing> standings = new ArrayList<>();
        for (int i = 0; i < entries.size(); i++) {
            double points = 0, wins = 0, draws = 0, losses = 0;
            for (Game g : games) {
                if (g.a() != i && g.b() != i) {
                    continue;
                }
                points += g.pointsFor(i);
                wins += g.winChance(i);
                draws += g.result().pDraw();
                losses += g.lossChance(i);
            }
            standings.add(new Standing(entries.get(i), points, wins, draws, losses));
        }
        standings.sort(Comparator.comparingDouble(Standing::points).reversed()
                .thenComparing(Comparator.comparingDouble(Standing::wins).reversed()));
        return standings;
    }
}
