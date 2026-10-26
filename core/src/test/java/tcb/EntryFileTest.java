package tcb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import tcb.deck.DeckList;
import tcb.deck.EntryFile;

class EntryFileTest {
    @TempDir
    Path dir;

    @Test
    void roundTripsMetadataAndKeepsTheDeckReadable() throws IOException {
        Path file = dir.resolve("sligh.txt");
        Instant created = Instant.parse("2026-10-25T21:30:00Z");
        Instant updated = Instant.parse("2026-10-26T09:12:44Z");
        new EntryFile(DeckList.of("sligh", "Mountain", "Mountain", "Goblin Guide"), created, updated,
                "Haste beats the blockers\n# that come down on turn 2.").write(file);

        EntryFile read = EntryFile.read(file);
        assertEquals(List.of("Mountain", "Mountain", "Goblin Guide"), read.deck().cards());
        assertEquals(created, read.created());
        assertEquals(updated, read.updated());
        assertEquals("Haste beats the blockers\n# that come down on turn 2.", read.notes());
        // the CLI reads the same file and sees only the cards
        assertEquals(List.of("Mountain", "Mountain", "Goblin Guide"), DeckList.read(file).cards());
    }

    @Test
    void oldFilesHaveNoCreatedDateAndUseTheirModificationTime() throws IOException {
        Path file = dir.resolve("old.txt");
        Files.writeString(file, "# Sligh\n2 Mountain\nGoblin Guide\n");
        Instant mtime = Instant.parse("2026-01-02T03:04:05Z");
        Files.setLastModifiedTime(file, FileTime.from(mtime));

        EntryFile read = EntryFile.read(file);
        assertNull(read.created());
        assertEquals(mtime, read.updated());
        assertEquals("", read.notes());
    }
}
