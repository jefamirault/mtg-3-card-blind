package tcb.engine;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import forge.LobbyPlayer;
import forge.ai.AiCache;
import forge.ai.LobbyPlayerAi;
import forge.deck.Deck;
import forge.game.Game;
import forge.game.GameOutcome;
import forge.game.GameRules;
import forge.game.GameType;
import forge.game.Match;
import forge.game.player.Player;
import forge.game.player.RegisteredPlayer;
import forge.util.MyRandom;
import tcb.deck.DeckList;
import tcb.deck.VintageValidator;

/**
 * Plays one 3 Card Blind game in Forge, replaying a {@link DecisionTape}, and reports either the
 * finished result or the next unexplored decision.
 *
 * 3CB setup: each player's 3 cards start in hand (opening hand size 3, nothing left in library),
 * no mulligans, a fixed starting player, and drawing from an empty library doesn't lose.
 */
public final class GameRunner {
    /** Seed for Forge's RNG outside chance nodes; replays must be deterministic. */
    static final long SEED = 0x3CB;
    private static final long GAME_THREAD_STACK = 64L * 1024 * 1024;

    private final Deck deckA;
    private final Deck deckB;
    private final int startingPlayer;
    private final boolean exhaustive;

    /** @param startingPlayer 0 if deck A is on the play, 1 if deck B is. */
    public GameRunner(DeckList a, DeckList b, int startingPlayer) {
        this(a, b, startingPlayer, false);
    }

    /** @param exhaustive branch on every option (see {@link SolverController}) */
    public GameRunner(DeckList a, DeckList b, int startingPlayer, boolean exhaustive) {
        ForgeEnv.init();
        this.deckA = VintageValidator.toForgeDeck(a);
        this.deckB = VintageValidator.toForgeDeck(b);
        this.startingPlayer = startingPlayer;
        this.exhaustive = exhaustive;
    }

    public enum Result {
        /** Deck A won. */
        A_WINS,
        B_WINS,
        DRAW
    }

    /**
     * Either a finished game ({@code result}) or an unexplored decision ({@code branch}). For games
     * started with {@link #start}, {@code continuation} is the game paused at that decision.
     */
    public record Outcome(Result result, Signals.Branch branch, List<String> trace, int turn, Continuation continuation) {
        public Outcome(Result result, Signals.Branch branch, List<String> trace, int turn) {
            this(result, branch, trace, turn, null);
        }

        public boolean isTerminal() {
            return result != null;
        }

        /** +1 if deck A won, -1 if deck B won, 0 for a draw. */
        public int value() {
            return switch (result) {
                case A_WINS -> 1;
                case B_WINS -> -1;
                case DRAW -> 0;
            };
        }
    }

    /**
     * Like {@link #run}, but plays the game on its own thread. If it reaches an unexplored
     * decision the game pauses there instead of stopping, and the outcome carries a
     * {@link Continuation} that the caller must either resume or abandon.
     */
    public Outcome start(DecisionTape tape) {
        Continuation.Handoff handoff = new Continuation.Handoff();
        tape.pauseAtNewDecisions(handoff);
        Thread game = new Thread(null, () -> {
            Object result;
            try {
                result = run(tape);
            } catch (Continuation.Abandoned a) {
                return;
            } catch (RuntimeException | Error e) {
                result = e;
            }
            try {
                handoff.toSearch(result);
            } catch (Continuation.Abandoned a) {
                // interrupted while handing over the result; nobody is waiting
            }
        }, "tcb-game", GAME_THREAD_STACK);
        game.setDaemon(true);
        game.start();
        return handoff.fromGame();
    }

    /**
     * A replayed game with the board along the way: {@code boards.get(i)} is the board when
     * {@code actions.get(i)} was decided (before it took effect), and the last board is where
     * the game stopped, so there is one more board than actions.
     */
    public record Watched(List<String> actions, List<Map<String, Object>> boards, Outcome outcome) {
    }

    /** Replays {@code tape} (usually {@link DecisionTape#following}) on the calling thread, recording the board. */
    public Watched watch(DecisionTape tape) {
        List<String> actions = new ArrayList<>();
        List<Map<String, Object>> boards = new ArrayList<>();
        Game[] game = new Game[1];
        tape.observe(action -> {
            actions.add(action);
            boards.add(BoardSnapshot.of(game[0]));
        });
        Outcome outcome = run(tape, g -> game[0] = g);
        boards.add(BoardSnapshot.of(game[0]));
        return new Watched(actions, boards, outcome);
    }

    /** Plays the game on the calling thread, replaying {@code tape}, until it ends or reaches a new decision. */
    public Outcome run(DecisionTape tape) {
        return run(tape, null);
    }

    /** @param created told the game once it's set up, before it starts; may be null */
    private Outcome run(DecisionTape tape, Consumer<Game> created) {
        resetIds();
        AiCache.clear();
        ChanceRandom random = new ChanceRandom(tape, SEED);
        MyRandom.setRandomDirect(random);

        List<RegisteredPlayer> players = new ArrayList<>();
        players.add(register(deckA, "A", tape));
        players.add(register(deckB, "B", tape));

        GameRules rules = new GameRules(GameType.Constructed);
        rules.setGamesPerMatch(1);
        rules.setNoEmptyLibraryLoss(true);
        Match match = new Match(rules, players, "3 Card Blind");
        Game game = match.createGame();
        game.setNoGUIUser();
        if (created != null) {
            created.accept(game);
        }
        random.attach(game);

        try {
            match.startGame(game);
        } catch (Signals.BranchReached b) {
            return new Outcome(null, b.branch, tape.trace(), game.getPhaseHandler().getTurn());
        } catch (Signals.PositionRepeated r) {
            tape.trace().add("(position repeats: the game can loop forever)");
            return new Outcome(Result.DRAW, null, tape.trace(), game.getPhaseHandler().getTurn());
        } catch (Signals.TurnLimitReached t) {
            return new Outcome(Result.DRAW, null, tape.trace(), game.getPhaseHandler().getTurn());
        }

        GameOutcome outcome = game.getOutcome();
        int turn = game.getPhaseHandler().getTurn();
        if (outcome == null || outcome.isDraw()) {
            return new Outcome(Result.DRAW, null, tape.trace(), turn);
        }
        boolean aWon = outcome.isWinner(players.get(0));
        return new Outcome(aWon ? Result.A_WINS : Result.B_WINS, null, tape.trace(), turn);
    }

    /**
     * Forge numbers abilities, triggers etc. from global counters; some engine and AI code orders
     * by those ids, so every replay must start from the same values. Starts well above any ids
     * handed out while loading the card database.
     */
    private static void resetIds() {
        int start = 1_000_000;
        forge.game.Game.resetIds(start);
        forge.game.spellability.SpellAbility.resetIds(start);
        forge.game.spellability.SpellAbilityStackInstance.resetIds(start);
        forge.game.trigger.Trigger.resetIds(start);
        forge.game.staticability.StaticAbility.resetIds(start);
        forge.game.replacement.ReplacementEffect.resetIds(start);
    }

    private RegisteredPlayer register(Deck deck, String name, DecisionTape tape) {
        RegisteredPlayer rp = new RegisteredPlayer(deck);
        rp.setStartingHand(VintageValidator.DECK_SIZE);
        rp.setPlayer(new SolverLobbyPlayer(name, tape, startingPlayer, exhaustive));
        return rp;
    }

    /** Lobby player that puts a {@link SolverController} in charge of its in-game player. */
    private static final class SolverLobbyPlayer extends LobbyPlayerAi {
        private final DecisionTape tape;
        private final int startingPlayer;
        private final boolean exhaustive;

        SolverLobbyPlayer(String name, DecisionTape tape, int startingPlayer, boolean exhaustive) {
            super(name, null);
            this.tape = tape;
            this.startingPlayer = startingPlayer;
            this.exhaustive = exhaustive;
        }

        @Override
        public Player createIngamePlayer(Game game, int id) {
            Player p = new Player(getName(), game, id);
            p.setFirstController(new SolverController(game, p, (LobbyPlayer) this, tape, startingPlayer, exhaustive));
            return p;
        }
    }
}
