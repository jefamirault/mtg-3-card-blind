package tcb.engine;

import java.util.Random;

import forge.game.Game;
import forge.game.GameStage;

/**
 * Forge's random source during solver replays. Random events that happen while a spell or ability
 * resolves (coin flips, dice, random discard, shuffles) become chance nodes on the
 * {@link DecisionTape}: every outcome is explored and weighted equally.
 *
 * Anything else (the pre-game coin toss, AI tie-breaking in heuristic fallbacks) and very wide
 * draws (more than {@link #MAX_OUTCOMES} outcomes) use a fixed seed, so replays stay deterministic.
 */
final class ChanceRandom extends Random {
    static final int MAX_OUTCOMES = 16;

    private final DecisionTape tape;
    private Game game;

    ChanceRandom(DecisionTape tape, long seed) {
        super(seed);
        this.tape = tape;
    }

    void attach(Game game) {
        this.game = game;
    }

    private boolean chanceApplies(int outcomes) {
        return game != null && outcomes > 1 && outcomes <= MAX_OUTCOMES
                && game.getAge() == GameStage.Play && game.getStack().isResolving();
    }

    @Override
    public int nextInt(int bound) {
        if (!chanceApplies(bound)) {
            return super.nextInt(bound);
        }
        return tape.chance(bound, game.getPhaseHandler().getTurn());
    }

    @Override
    public int nextInt(int origin, int bound) {
        return origin + nextInt(bound - origin);
    }

    @Override
    public boolean nextBoolean() {
        if (!chanceApplies(2)) {
            return super.nextBoolean();
        }
        return tape.chance(2, game.getPhaseHandler().getTurn()) == 0;
    }
}
