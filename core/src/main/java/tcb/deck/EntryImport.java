package tcb.deck;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
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
 * <p>
 * A line with {@code |} in it is an entry of its own, cards separated by {@code |}, optionally
 * with a name and a comma before the first card: {@code Sligh, Goblin Guide | 2 Mountain}. The
 * comma only separates a name if what follows it is a card, so {@code Khan, Vengeful Heart | ...}
 * is three cards. A first part with no comma is the name only if three cards follow it
 * ({@code Sligh | Goblin Guide | Mountain | Mountain}). Several such lines in a row are several entries.
 */
public final class EntryImport {
    private EntryImport() {
    }

    /**
     * One block of the text: the entry it describes, and what's wrong with the block itself.
     * {@code generated} is true when the name was made from the cards.
     */
    public record Block(String name, List<String> cards, String notes, List<String> problems, boolean generated) {
        public Block(String name, List<String> cards, String notes, List<String> problems) {
            this(name, cards, notes, problems, false);
        }

        public Block withName(String newName) {
            return new Block(newName, cards, notes, problems, generated);
        }
    }

    private static final Pattern NOTES = Pattern.compile("^#\\s*notes:\\s?(.*)$");
    private static final Pattern OTHER_META = Pattern.compile("^#\\s*(created|updated):.*$");
    private static final Pattern EXPLICIT_NAME = Pattern.compile("^(?:name:|#)\\s*(.+)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern COUNTED = Pattern.compile("^\\d+\\s*x?\\s+.+$");
    /** The longest entry name. */
    public static final int MAX_NAME = 40;

    public static List<Block> parse(String text) {
        ForgeEnv.init(); // telling names from cards needs the card database
        List<Block> blocks = new ArrayList<>();
        List<String> lines = new ArrayList<>();
        for (String raw : (text + "\n").split("\\R", -1)) {
            String line = raw.strip();
            if (line.isEmpty() || isOneLineEntry(line)) {
                if (!lines.isEmpty()) {
                    blocks.add(block(lines, false));
                    lines = new ArrayList<>();
                }
                if (!line.isEmpty()) {
                    blocks.add(block(oneLineEntry(line), true));
                }
            } else {
                lines.add(line);
            }
        }
        return blocks;
    }

    /** {@code [Name,] Card | Card | Card}; '#' lines (notes, names) keep their text as it is. */
    private static boolean isOneLineEntry(String line) {
        return line.indexOf('|') >= 0 && !line.startsWith("#") && !EXPLICIT_NAME.matcher(line).matches();
    }

    /** A one-line entry as the lines of a block: {@code Name: ...} if it has a name, then a card per line. */
    private static List<String> oneLineEntry(String line) {
        List<String> lines = new ArrayList<>();
        for (String part : line.split("\\|")) {
            if (!part.isBlank()) {
                lines.add(part.strip());
            }
        }
        if (!lines.isEmpty()) {
            String first = lines.get(0);
            int comma = nameComma(first);
            if (comma >= 0) {
                lines.set(0, first.substring(comma + 1).strip());
                lines.add(0, "Name: " + first.substring(0, comma).strip());
            }
        }
        return lines;
    }

    /**
     * Where a name ends in the first part of a one-line entry ("Sligh, Goblin Guide"), or -1 if it
     * has none: the first comma that has a card (maybe counted) after it, unless the whole part is
     * already a card, since card names have commas too ("Khan, Vengeful Heart").
     */
    private static int nameComma(String part) {
        if (isCard(part)) {
            return -1;
        }
        for (int i = part.indexOf(','); i > 0; i = part.indexOf(',', i + 1)) {
            if (isCard(part.substring(i + 1))) {
                return i;
            }
        }
        return -1;
    }

    /** Whether this line names a known card, with or without a count ("2 Mountain"). */
    private static boolean isCard(String line) {
        try {
            List<String> cards = DeckList.parse("import", line.strip()).cards();
            return !cards.isEmpty() && VintageValidator.lookup(cards.get(0)) != null;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** @param oneLine a one-line entry: its name was found already, so an unknown first card stays a card */
    private static Block block(List<String> lines, boolean oneLine) {
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
            } else if (body.size() > 1 && !COUNTED.matcher(first).matches() && firstLineIsName(body, oneLine)) {
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
        boolean generated = name == null;
        if (generated) {
            name = nameFrom(cards);
        }
        return new Block(name, cards, String.join("\n", notes), problems, generated);
    }

    private static boolean firstLineIsName(List<String> body, boolean oneLine) {
        int with = cardCount(body);
        int without = cardCount(body.subList(1, body.size()));
        return (without == VintageValidator.DECK_SIZE && with != VintageValidator.DECK_SIZE)
                || (!oneLine && VintageValidator.lookup(body.get(0)) == null);
    }

    /** Cards in these lines, or -1 if they don't parse. */
    private static int cardCount(List<String> lines) {
        try {
            return DeckList.parse("import", String.join("\n", lines)).cards().size();
        } catch (IllegalArgumentException e) {
            return -1;
        }
    }

    /** Characters of each card that go into a name made from the cards. */
    static final int NAME_PART = 4;

    /**
     * "GoblMounMoun" for Goblin Guide, Mountain, Mountain: a name for an unnamed block, the first
     * {@value #NAME_PART} letters and digits of each card (Æ as Ae, accents dropped), in
     * alphabetical order. Every copy counts, so the same cards give the same name whatever order
     * they're written in. The web form suggests the same name first ({@code suggestedNames}).
     */
    static String nameFrom(List<String> cards) {
        List<String> words = new ArrayList<>();
        for (String card : cards) {
            words.add(Normalizer.normalize(card.replace("Æ", "Ae").replace("æ", "ae"), Normalizer.Form.NFD)
                    .replaceAll("[^A-Za-z0-9]", ""));
        }
        words.sort(String.CASE_INSENSITIVE_ORDER.thenComparing(Comparator.naturalOrder()));
        StringBuilder sb = new StringBuilder();
        for (String w : words) {
            sb.append(w, 0, Math.min(NAME_PART, w.length()));
        }
        String name = sb.toString();
        name = name.length() > MAX_NAME ? name.substring(0, MAX_NAME) : name;
        return name.isEmpty() ? "Imported entry" : name;
    }

    /** The name with " n" after it, shortened at a word if needed to stay within {@link #MAX_NAME}. */
    public static String numbered(String name, int n) {
        String suffix = " " + n;
        return cutAtWord(name, MAX_NAME - suffix.length()) + suffix;
    }

    private static String cutAtWord(String s, int max) {
        if (s.length() <= max) {
            return s;
        }
        String cut = s.substring(0, max);
        int space = cut.lastIndexOf(' ');
        if (space > 0 && !Character.isWhitespace(s.charAt(max))) {
            cut = cut.substring(0, space);
        }
        return cut.replaceAll("[ -]+$", "");
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
