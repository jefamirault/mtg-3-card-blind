package tcb.engine;

import java.util.ArrayList;
import java.util.List;

import forge.LobbyPlayer;
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

    private final Deck deckA;
    private final Deck deckB;
    private final int startingPlayer;

    /** @param startingPlayer 0 if deck A is on the play, 1 if deck B is. */
    public GameRunner(DeckList a, DeckList b, int startingPlayer) {
        ForgeEnv.init();
        this.deckA = VintageValidator.toForgeDeck(a);
        this.deckB = VintageValidator.toForgeDeck(b);
        this.startingPlayer = startingPlayer;
    }

    public enum Result {
        /** Deck A won. */
        A_WINS,
        B_WINS,
        DRAW
    }

    /** Either a finished game ({@code result}) or an unexplored decision ({@code branch}). */
    public record Outcome(Result result, Signals.Branch branch, List<String> trace, int turn) {
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

    public Outcome run(DecisionTape tape) {
        resetIds();
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
        rp.setPlayer(new SolverLobbyPlayer(name, tape, startingPlayer));
        return rp;
    }

    /** Lobby player that puts a {@link SolverController} in charge of its in-game player. */
    private static final class SolverLobbyPlayer extends LobbyPlayerAi {
        private final DecisionTape tape;
        private final int startingPlayer;

        SolverLobbyPlayer(String name, DecisionTape tape, int startingPlayer) {
            super(name, null);
            this.tape = tape;
            this.startingPlayer = startingPlayer;
        }

        @Override
        public Player createIngamePlayer(Game game, int id) {
            Player p = new Player(getName(), game, id);
            p.setFirstController(new SolverController(game, p, (LobbyPlayer) this, tape, startingPlayer));
            return p;
        }
    }
}
