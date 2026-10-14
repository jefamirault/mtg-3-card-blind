package tcb.deck;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A 3 Card Blind entry: a name and the card names in it (one entry per copy).
 *
 * Text format, one card per line, optional leading count; '#' starts a comment:
 * <pre>
 * # Sligh
 * 2 Mountain
 * Goblin Guide
 * </pre>
 */
public final class DeckList {
    private static final Pattern LINE = Pattern.compile("^(?:(\\d+)\\s*x?\\s+)?(.+)$");

    private final String name;
    private final List<String> cards;

    public DeckList(String name, List<String> cards) {
        this.name = name;
        this.cards = List.copyOf(cards);
    }

    public static DeckList of(String name, String... cards) {
        return new DeckList(name, List.of(cards));
    }

    public static DeckList parse(String name, String text) {
        List<String> cards = new ArrayList<>();
        for (String raw : text.split("\\R")) {
            String line = raw.strip();
            int hash = line.indexOf('#');
            if (hash >= 0) {
                line = line.substring(0, hash).strip();
            }
            if (line.isEmpty()) {
                continue;
            }
            Matcher m = LINE.matcher(line);
            if (!m.matches()) {
                throw new IllegalArgumentException("Unparseable line: " + raw);
            }
            int count = m.group(1) == null ? 1 : Integer.parseInt(m.group(1));
            cards.addAll(Collections.nCopies(count, m.group(2).strip()));
        }
        return new DeckList(name, cards);
    }

    /** Reads a deck file; the deck is named after the file (without extension). */
    public static DeckList read(Path file) throws IOException {
        String fileName = file.getFileName().toString();
        int dot = fileName.lastIndexOf('.');
        String name = dot > 0 ? fileName.substring(0, dot) : fileName;
        return parse(name, Files.readString(file));
    }

    public String name() {
        return name;
    }

    public List<String> cards() {
        return cards;
    }

    @Override
    public String toString() {
        return name + " " + cards;
    }
}
