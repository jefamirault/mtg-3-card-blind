package tcb.solve;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import tcb.deck.DeckList;
import tcb.engine.BoardSnapshot;
import tcb.engine.DecisionTape;
import tcb.engine.GameRunner;
import tcb.engine.GameRunner.Watched;

/**
 * Replays a game along a recorded line (such as a saved optimal line) and returns the board at
 * every step of every turn, for an animated replay. Works from the line's text, so tournaments saved before
 * replays existed can be watched too.
 */
public final class GameReplay {
    /** The decisions in a line; the rest are notes, such as "(position repeats: ...)" and "=> A wins ...". */
    private static final Pattern DECISION = Pattern.compile("^(A|B|Chance) .+: .*");

    private GameReplay() {
    }

    /**
     * @param line      the game's line, as {@link Solver.GameResult#principalVariation()} gives it
     * @param turnLimit the turn limit it was solved with, in Forge turns
     * @return {@code frames}: one per step of each turn, in order, each with the board when the
     *         step ended ({@link tcb.engine.BoardSnapshot}), its {@code turn} (in turn cycles),
     *         {@code active} player and {@code phase}, and {@code did}, the actions taken during
     *         it (often none); and {@code end}, how the line ends ("A wins on turn 4 (B's
     *         turn)"), if it says
     */
    public static Map<String, Object> replay(DeckList a, DeckList b, int startingPlayer, List<String> line, int turnLimit) {
        List<String> decisions = line.stream().filter(l -> DECISION.matcher(l).matches()).toList();
        Watched w = new GameRunner(a, b, startingPlayer).watch(DecisionTape.following(decisions, turnLimit));
        List<Map<String, Object>> frames = new ArrayList<>();
        for (GameRunner.Stop stop : w.stops()) {
            Map<String, Object> f = new LinkedHashMap<>(stop.board());
            f.put("forgeTurn", stop.forgeTurn());
            f.put("turn", Turns.cycle(stop.forgeTurn()));
            f.put("active", stop.active());
            f.put("phase", stop.phase() == null ? "" : BoardSnapshot.phaseName(stop.phase()));
            f.put("did", stop.actions());
            frames.add(f);
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("frames", frames);
        m.put("end", line.stream().filter(l -> l.startsWith("=>")).map(l -> l.substring(2).strip()).findFirst().orElse(null));
        return m;
    }
}
