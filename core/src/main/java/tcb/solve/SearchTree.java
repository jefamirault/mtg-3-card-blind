package tcb.solve;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPOutputStream;

import com.google.gson.stream.JsonWriter;

import tcb.engine.Signals.Branch;

/**
 * The game tree a {@link Solver} searched, for browsing after a tournament: every decision it
 * expanded, with the value it found for each option it tried. Values are what the search proved,
 * from deck A's point of view: alpha-beta stops looking once an option can't change the result,
 * so many are bounds ("A can't win") rather than exact, and options it never tried are left out.
 *
 * Only the passes of the last turn limit that finished are kept (see {@link Solver#solve()}), and
 * each pass records at most {@link #MAX_NODES} nodes; the rest show as not saved.
 */
public final class SearchTree {
    /** Most nodes recorded per pass, so one game's tree stays a few MB at worst. */
    public static final int MAX_NODES = 50_000;

    /** A searched position: a decision (branch set) or a leaf (end or seen set). */
    static final class Node {
        final Branch branch;
        final String end;
        final boolean seen;
        final List<Child> children = new ArrayList<>();
        int best = -1;

        private Node(Branch branch, String end, boolean seen) {
            this.branch = branch;
            this.end = end;
            this.seen = seen;
        }
    }

    /**
     * One option tried at a decision.
     *
     * @param bound '=' exact, '>' at least {@code value}, '<' at most {@code value}
     * @param node  what the option leads to; null if the node limit had been reached
     */
    record Child(int option, double value, char bound, Node node) {
    }

    private record Pass(String name, Node root) {
    }

    private final List<Pass> passes = new ArrayList<>();
    private List<Pass> kept = List.of();
    private int keptTurnLimit;
    private int nodes;
    private boolean truncated;
    private boolean keptTruncated;

    /** Starts recording a pass; drops passes left over from a turn limit that didn't finish. */
    void startPass(boolean newTurnLimit) {
        if (newTurnLimit) {
            passes.clear();
            truncated = false;
        }
        nodes = 0;
    }

    void endPass(String name, Node root) {
        passes.add(new Pass(name, root));
        truncated |= nodes >= MAX_NODES;
    }

    /** The passes recorded since the last {@link #startPass(boolean) new turn limit} are the result's. */
    void keep(int turnLimit) {
        kept = List.copyOf(passes);
        keptTurnLimit = turnLimit;
        keptTruncated = truncated;
        truncated = false;
    }

    boolean isEmpty() {
        return kept.isEmpty();
    }

    /** A decision about to be searched; null once the pass has recorded {@link #MAX_NODES}. */
    Node decision(Branch branch) {
        return room() ? new Node(branch, null, false) : null;
    }

    /** A finished game, e.g. "A wins on turn 4 (B's turn)". */
    Node end(String how) {
        return room() ? new Node(null, how, false) : null;
    }

    /** A position whose value came from the transposition table: it was searched on another line. */
    Node seen() {
        return room() ? new Node(null, null, true) : null;
    }

    private boolean room() {
        if (nodes >= MAX_NODES) {
            return false;
        }
        nodes++;
        return true;
    }

    /**
     * The tree as gzipped JSON:
     * <pre>
     * {"turnLimit": 25, "truncated": false, "passes": [{"name": "can A force a win?", "root": NODE}]}
     * NODE: {"p": 0|1|-1 (chance), "k": "attack", "t": turn cycle, "o": [option labels], "best": i,
     *        "c": [{"i": option, "v": value, "b": "=" | "&gt;=" | "&lt;=", "n": NODE or null (not saved)}]}
     *     | {"end": "A wins on turn 4 (B's turn)"} | {"seen": true}
     * </pre>
     */
    public byte[] toGzipJson() {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (Writer w = new OutputStreamWriter(new GZIPOutputStream(bytes), StandardCharsets.UTF_8);
             JsonWriter json = new JsonWriter(w)) {
            json.beginObject();
            json.name("turnLimit").value(keptTurnLimit / 2);
            json.name("truncated").value(keptTruncated);
            json.name("maxNodes").value(MAX_NODES);
            json.name("passes").beginArray();
            for (Pass p : kept) {
                json.beginObject().name("name").value(p.name()).name("root");
                write(json, p.root());
                json.endObject();
            }
            json.endArray();
            json.endObject();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return bytes.toByteArray();
    }

    private static void write(JsonWriter json, Node n) throws IOException {
        if (n == null) {
            json.nullValue();
            return;
        }
        json.beginObject();
        if (n.end != null) {
            json.name("end").value(n.end);
        } else if (n.seen) {
            json.name("seen").value(true);
        } else {
            Branch b = n.branch;
            json.name("p").value(b.player());
            json.name("k").value(b.kind());
            json.name("t").value(Turns.cycle(b.turn()));
            json.name("o").beginArray();
            for (String label : b.labels()) {
                json.value(label);
            }
            json.endArray();
            json.name("best").value(n.best);
            json.name("c").beginArray();
            for (Child c : n.children) {
                json.beginObject();
                json.name("i").value(c.option());
                json.name("v").value(c.value());
                json.name("b").value(c.bound() == '=' ? "=" : c.bound() == '>' ? ">=" : "<=");
                json.name("n");
                write(json, c.node());
                json.endObject();
            }
            json.endArray();
        }
        json.endObject();
    }
}
