package tcb.solve;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Consumer;
import java.util.function.Supplier;

import tcb.deck.DeckList;
import tcb.deck.VintageValidator;
import tcb.engine.NondeterministicReplayException;
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

    /**
     * One solved game; {@code result.value()} is from deck {@code a}'s point of view.
     *
     * @param cached true if the result came from the {@link ResultCache} instead of a search
     */
    public record Game(int a, int b, int startingPlayer, GameResult result, boolean cached) {
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

    /** A game the tournament was stopped before it finished. */
    public record Unplayed(int a, int b, int startingPlayer) {
    }

    /**
     * @param games     the games played; standings count only these
     * @param notPlayed games left unplayed because the tournament was stopped (empty otherwise)
     */
    public record Results(List<DeckList> entries, List<Game> games, List<Standing> standings, List<Unplayed> notPlayed) {
        /** True if both games between {@code i} and {@code j} were played. */
        public boolean bothPlayed(int i, int j) {
            return notPlayed.stream().noneMatch(u -> (u.a() == i && u.b() == j) || (u.a() == j && u.b() == i));
        }

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
            return notPlayed.isEmpty() && games.stream().allMatch(g -> g.result().complete());
        }
    }

    /** A game being solved right now; {@code solver.progress()} gives its live statistics. */
    public record Running(int a, int b, int startingPlayer, Solver solver) {
    }

    /**
     * Stops a tournament early: games not started yet are skipped and games being solved are
     * cancelled. Both end up in {@link Results#notPlayed()}; finished games are kept.
     */
    public static final class Stop {
        private volatile boolean requested;
        private final Set<Solver> solvers = ConcurrentHashMap.newKeySet();

        public void request() {
            requested = true;
            solvers.forEach(Solver::cancel);
        }

        public boolean requested() {
            return requested;
        }

        // Registering before checking the flag (and request() setting it before cancelling) means
        // every solver is either cancelled by request() or sees the flag here.
        private void register(Solver solver) {
            solvers.add(solver);
            if (requested) {
                solver.cancel();
            }
        }

        private void unregister(Solver solver) {
            solvers.remove(solver);
        }
    }

    private Tournament() {
    }

    public static Results run(List<DeckList> entries, Options options, int threads, Consumer<String> progress) {
        return run(entries, options, threads, progress, ConcurrentHashMap.newKeySet(), new Stop(), null);
    }

    /**
     * @param progress one line per finished game
     * @param running  games currently being solved: each is added when its search starts and
     *                 removed after its line has gone to {@code progress}. Must be thread-safe.
     * @param stop     to stop the tournament early from another thread
     * @param cache    solved games to reuse, and where to save new ones; null to solve every game
     */
    public static Results run(List<DeckList> entries, Options options, int threads, Consumer<String> progress,
                              Collection<Running> running, Stop stop, ResultCache cache) {
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
            // A null game is one left unplayed by a stop.
            List<Future<Game>> futures = new ArrayList<>();
            for (int i = 0; i < entries.size(); i++) {
                for (int j = i + 1; j < entries.size(); j++) {
                    for (int start = 0; start < 2; start++) {
                        final int a = i, b = j, s = start;
                        futures.add(pool.submit(() -> {
                            if (stop.requested()) {
                                return null;
                            }
                            GameResult r = cache == null ? null : cache.get(entries.get(a), entries.get(b), s, options);
                            boolean cached = r != null;
                            Running[] live = new Running[1];
                            if (!cached) {
                                try {
                                    Solver[] last = new Solver[1];
                                    r = retryingAlone(() -> {
                                        if (live[0] != null) {
                                            running.remove(live[0]);
                                        }
                                        Solver solver = new Solver(entries.get(a), entries.get(b), s, options);
                                        last[0] = solver;
                                        live[0] = new Running(a, b, s, solver);
                                        running.add(live[0]);
                                        stop.register(solver);
                                        try {
                                            return solver.solve();
                                        } finally {
                                            stop.unregister(solver);
                                        }
                                    });
                                    if (last[0].cancelled() && !r.complete()) {
                                        running.remove(live[0]);
                                        return null;
                                    }
                                    if (cache != null) {
                                        cache.put(entries.get(a), entries.get(b), s, options, r);
                                    }
                                } catch (RuntimeException e) {
                                    // Don't lose the whole tournament to one game: score it as an unproven draw.
                                    r = new GameResult(0, 1, 0, 0, false, List.of("solver error: " + e.getMessage()),
                                            0, java.time.Duration.ZERO);
                                }
                            }
                            Game game = new Game(a, b, s, r, cached);
                            progress.accept(String.format("%s vs %s (%s on the play): %s%s%s",
                                    entries.get(a).name(), entries.get(b).name(),
                                    entries.get(s == 0 ? a : b).name(),
                                    r.describe().replace("A ", entries.get(a).name() + " ").replace("B ", entries.get(b).name() + " "),
                                    r.complete() ? "" : " (unproven beyond turn " + r.provenTurnLimit() + ")",
                                    cached ? " (cached)" : ""));
                            if (live[0] != null) {
                                running.remove(live[0]);
                            }
                            return game;
                        }));
                    }
                }
            }
            List<Game> games = new ArrayList<>();
            List<Unplayed> notPlayed = new ArrayList<>();
            int k = 0;
            for (int i = 0; i < entries.size(); i++) {
                for (int j = i + 1; j < entries.size(); j++) {
                    for (int start = 0; start < 2; start++) {
                        Game g = futures.get(k++).get();
                        if (g != null) {
                            games.add(g);
                        } else {
                            notPlayed.add(new Unplayed(i, j, start));
                        }
                    }
                }
            }
            return new Results(List.copyOf(entries), games, standings(entries, games), notPlayed);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("solver failed: " + e.getCause(), e.getCause());
        } finally {
            pool.shutdownNow();
        }
    }

    /** Held shared while a game is solved, and exclusively by a retry; see {@link #retryingAlone}. */
    static final ReentrantReadWriteLock SOLVING = new ReentrantReadWriteLock();

    /**
     * Runs {@code solve}. Games are normally solved in parallel; if one fails because its replays
     * weren't deterministic (most likely Forge state shared with another game, see issue #6), it
     * is solved again with no other game running. Games already being solved finish first, and
     * no new ones start until the retry is done.
     */
    static <T> T retryingAlone(Supplier<T> solve) {
        SOLVING.readLock().lock();
        try {
            return solve.get();
        } catch (RuntimeException e) {
            if (!NondeterministicReplayException.causedBy(e)) {
                throw e;
            }
        } finally {
            SOLVING.readLock().unlock();
        }
        SOLVING.writeLock().lock();
        try {
            return solve.get();
        } finally {
            SOLVING.writeLock().unlock();
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
