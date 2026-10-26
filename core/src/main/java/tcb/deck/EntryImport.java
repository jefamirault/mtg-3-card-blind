package tcb.deck;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import tcb.engine.ForgeEnv;

/**
 * Many entries in one text, for importing: blocks separated by blank lines, each a name and then
 * one card per line (deck file syntax: optional count, '#' comments). For example:
 * <pre>
 * Sligh
 * 2 Mountain
 * Goblin Guide
 *
 * Name: Lotus storm
 * # notes: three of them
 * 3 Black Lotus
 * </pre>
 * The first line is the name if it isn't a card, or if the rest make a 3-card deck and all the
 * lines wouldn't (so a name that's also a card's, like "Burn", still works). A block without a name gets one from its cards. {@code Name: ...} or {@code # ...}
 * as the first line names it explicitly.
 * {@code # notes:} lines become the entry's notes. {@link #export} writes this format.
 */
public final class EntryImport {
    private EntryImport() {
    }

    /** One block of the text: the entry it describes, and what's wrong with the block itself. */
    public record Block(String name, List<String> cards, String notes, List<String> problems) {
    }

    private static final Pattern NOTES = Pattern.compile("^#\\s*notes:\\s?(.*)$");
    private static final Pattern OTHER_META = Pattern.compile("^#\\s*(created|updated):.*$");
    private static final Pattern EXPLICIT_NAME = Pattern.compile("^(?:name:|#)\\s*(.+)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern COUNTED = Pattern.compile("^\\d+\\s*x?\\s+.+$");
    private static final int MAX_NAME = 40;

    public static List<Block> parse(String text) {
        ForgeEnv.init(); // telling names from cards needs the card database
        List<Block> blocks = new ArrayList<>();
        List<String> lines = new ArrayList<>();
        for (String raw : (text + "\n").split("\\R", -1)) {
            String line = raw.strip();
            if (line.isEmpty()) {
                if (!lines.isEmpty()) {
                    blocks.add(block(lines));
                    lines = new ArrayList<>();
                }
            } else {
                lines.add(line);
            }
        }
        return blocks;
    }

    private static Block block(List<String> lines) {
        List<String> notes = new ArrayList<>();
        List<String> body = new ArrayList<>();
        String name = null;
        for (String line : lines) {
            Matcher n = NOTES.matcher(line);
            if (n.matches()) {
                notes.add(n.group(1));
            } else if (!OTHER_META.matcher(line).matches()) {
                body.add(line);
            }
        }
        if (!body.isEmpty()) {
            String first = body.get(0);
            Matcher explicit = EXPLICIT_NAME.matcher(first);
            if (explicit.matches()) {
                name = explicit.group(1).strip();
                body.remove(0);
            } else if (body.size() > 1 && !COUNTED.matcher(first).matches() && firstLineIsName(body)) {
                name = first;
                body.remove(0);
            }
        }
        List<String> problems = new ArrayList<>();
        List<String> cards = List.of();
        try {
            cards = DeckList.parse("import", String.join("\n", body)).cards();
        } catch (IllegalArgumentException e) {
            problems.add(e.getMessage());
        }
        if (name == null) {
            name = nameFrom(cards);
        }
        return new Block(name, cards, String.join("\n", notes), problems);
    }

    private static boolean firstLineIsName(List<String> body) {
        int with = cardCount(body);
        int without = cardCount(body.subList(1, body.size()));
        return (without == VintageValidator.DECK_SIZE && with != VintageValidator.DECK_SIZE)
                || VintageValidator.lookup(body.get(0)) == null;
    }

    /** Cards in these lines, or -1 if they don't parse. */
    private static int cardCount(List<String> lines) {
        try {
            return DeckList.parse("import", String.join("\n", lines)).cards().size();
        } catch (IllegalArgumentException e) {
            return -1;
        }
    }

    /** "Goblin Guide - Mountain": a name for an unnamed block, in the characters names allow. */
    static String nameFrom(List<String> cards) {
        String name = String.join(" - ", new LinkedHashSet<>(cards)).replaceAll("[^A-Za-z0-9 _'-]", "").strip();
        name = name.replaceAll("^[^A-Za-z0-9]+", "");
        if (name.length() > MAX_NAME) {
            name = name.substring(0, MAX_NAME).strip();
        }
        return name.isEmpty() ? "Imported entry" : name;
    }

    /** Entries in the import format: each one's name, notes and cards, blank lines between. */
    public static String export(List<EntryFile> entries) {
        StringBuilder sb = new StringBuilder();
        for (EntryFile e : entries) {
            if (!sb.isEmpty()) {
                sb.append('\n');
            }
            sb.append("Name: ").append(e.deck().name()).append('\n');
            if (!e.notes().isBlank()) {
                for (String line : e.notes().strip().split("\\R", -1)) {
                    sb.append("# notes: ").append(line.stripTrailing()).append('\n');
                }
            }
            for (String card : e.deck().cards()) {
                sb.append(card).append('\n');
            }
        }
        return sb.toString();
    }
}
