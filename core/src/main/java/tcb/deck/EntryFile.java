package tcb.deck;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * An entry's deck file with its metadata. The metadata is kept in comment lines at the top, so
 * {@link DeckList#read} (and the CLI) still read the same file:
 * <pre>
 * # created: 2026-10-25T21:30:00Z
 * # updated: 2026-10-26T09:12:44Z
 * # notes: Haste beats the blockers
 * # notes: that come down on turn 2.
 * 2 Mountain
 * Goblin Guide
 * </pre>
 *
 * @param created when the entry was added; null for files written before entries had dates
 * @param updated when it last changed: the {@code updated} line, else the file's modification time
 * @param notes   free text, possibly several lines; empty if none
 */
public record EntryFile(DeckList deck, Instant created, Instant updated, String notes) {
    private static final Pattern META = Pattern.compile("^#\\s*(created|updated|notes):\\s?(.*)$");

    public static EntryFile read(Path file) throws IOException {
        DeckList deck = DeckList.read(file);
        Instant created = null;
        Instant updated = null;
        List<String> notes = new ArrayList<>();
        for (String line : Files.readAllLines(file)) {
            Matcher m = META.matcher(line.strip());
            if (!m.matches()) {
                continue;
            }
            String value = m.group(2);
            switch (m.group(1)) {
                case "created" -> created = instant(value);
                case "updated" -> updated = instant(value);
                default -> notes.add(value);
            }
        }
        if (updated == null) {
            updated = Files.getLastModifiedTime(file).toInstant();
        }
        return new EntryFile(deck, created, updated, String.join("\n", notes));
    }

    /** Writes the metadata lines, then one card per line. */
    public void write(Path file) throws IOException {
        StringBuilder sb = new StringBuilder();
        if (created != null) {
            sb.append("# created: ").append(created).append('\n');
        }
        if (updated != null) {
            sb.append("# updated: ").append(updated).append('\n');
        }
        if (!notes.isBlank()) {
            for (String line : notes.strip().split("\\R", -1)) {
                sb.append("# notes: ").append(line.stripTrailing()).append('\n');
            }
        }
        for (String card : deck.cards()) {
            sb.append(card).append('\n');
        }
        Files.writeString(file, sb);
    }

    private static Instant instant(String value) {
        try {
            return Instant.parse(value.strip());
        } catch (RuntimeException e) {
            return null; // a hand-edited date we can't read: as if there were none
        }
    }
}
