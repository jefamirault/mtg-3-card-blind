package tcb.solve;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * Points per game for the standings. Scoring only ranks results, it never changes a search, so it
 * isn't part of the {@link ResultCache} key, and saved results can be re-scored.
 */
public enum Scoring {
    /** Win 3, draw 1, loss 0: a draw is worth a third of a win. */
    THREE_ONE("3-1-0", 3, 1, false),
    /**
     * Win 2, draw 1, loss 0, shown as a percentage of the points available: 3cardblind.com's
     * scoring (win 1, draw 0.5) with whole-number points.
     */
    TWO_ONE("2-1-0", 2, 1, true);

    private final String id;
    private final int win;
    private final int draw;
    private final boolean percent;

    Scoring(String id, int win, int draw, boolean percent) {
        this.id = id;
        this.win = win;
        this.draw = draw;
        this.percent = percent;
    }

    /** As written on the command line and in JSON, e.g. {@code "3-1-0"}. */
    public String id() {
        return id;
    }

    public int win() {
        return win;
    }

    public int draw() {
        return draw;
    }

    /** True if standings show each deck's points as a percentage of the points available. */
    public boolean percent() {
        return percent;
    }

    /** Expected points for one game. */
    public double points(double pWin, double pDraw) {
        return win * pWin + draw * pDraw;
    }

    /** Points as a percentage of {@code games} × a win; 0 when no games were played. */
    public double percentOf(double points, double games) {
        return games <= 0 ? 0 : 100 * points / (win * games);
    }

    public static Scoring parse(String s) {
        for (Scoring sc : values()) {
            if (sc.id.equals(s.strip())) {
                return sc;
            }
        }
        throw new IllegalArgumentException("Unknown scoring " + s + "; use one of "
                + Arrays.stream(values()).map(Scoring::id).collect(Collectors.joining(", ")));
    }

    @Override
    public String toString() {
        return id;
    }
}
