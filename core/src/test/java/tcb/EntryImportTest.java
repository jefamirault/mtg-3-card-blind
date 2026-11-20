package tcb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import tcb.deck.DeckList;
import tcb.deck.EntryFile;
import tcb.deck.EntryImport;
import tcb.deck.EntryImport.Block;

class EntryImportTest {

    @Test
    void splitsBlocksAndFindsNames() {
        List<Block> blocks = EntryImport.parse("""
                Sligh
                2 Mountain
                Goblin Guide

                Name: Lotus storm
                # notes: three of them
                # notes: really
                3 Black Lotus


                # Swords
                Plains
                Plains
                Swords to Plowshares
                """);
        assertEquals(3, blocks.size());
        assertEquals(new Block("Sligh", List.of("Mountain", "Mountain", "Goblin Guide"), "", List.of()), blocks.get(0));
        assertEquals(new Block("Lotus storm", List.of("Black Lotus", "Black Lotus", "Black Lotus"), "three of them\nreally", List.of()),
                blocks.get(1));
        assertEquals("Swords", blocks.get(2).name());
        assertEquals(List.of("Plains", "Plains", "Swords to Plowshares"), blocks.get(2).cards());
    }

    @Test
    void aBlockOfCardsGetsANameFromThem() {
        Block b = EntryImport.parse("Goblin Guide\nMountain\nMountain\n").get(0);
        assertEquals("GoblMounMoun", b.name());
        assertEquals(List.of("Goblin Guide", "Mountain", "Mountain"), b.cards());
        assertTrue(b.generated());
        assertEquals("ChalChanFoun",
                EntryImport.parse("Chancellor of the Annex\nFountain of Cho\nChalice of the Void\n").get(0).name());
    }

    @Test
    void generatedNamesDontDependOnCardOrder() {
        assertEquals("PlaiPlaiSwor", EntryImport.parse("Swords to Plowshares\n2 Plains\n").get(0).name());
        assertEquals("PlaiPlaiSwor", EntryImport.parse("Plains\nSwords to Plowshares\nPlains\n").get(0).name());
    }

    @Test
    void generatedNamesSkipPunctuationAndSpaces() {
        // "Ulamog, the Infinite Gyre" loses its comma; "Ice Cauldron" its space
        assertEquals("IceCIslaUlam",
                EntryImport.parse("Ulamog, the Infinite Gyre\nIsland\nIce Cauldron\n").get(0).name());
    }

    @Test
    void numberedNamesStayWithinTheLimit() {
        assertEquals("Goblin Guide 2", EntryImport.numbered("Goblin Guide", 2));
        assertEquals("Leyline of Anticipation - Vryn 2", EntryImport.numbered("Leyline of Anticipation - Vryn Wingmare", 2));
        assertTrue(EntryImport.numbered("Leyline of Anticipation - Vryn Wingmare", 10).length() <= 40);
    }

    @Test
    void aNameThatIsAlsoACardStillNamesTheBlock() {
        Block b = EntryImport.parse("Burn\n3 Lightning Bolt\n").get(0);
        assertEquals("Burn", b.name());
        assertEquals(List.of("Lightning Bolt", "Lightning Bolt", "Lightning Bolt"), b.cards());
    }

    @Test
    void unknownFirstLineIsTheNameEvenWithFewCards() {
        // a name and two cards: the deck is short, which validation reports, but the name is right
        Block b = EntryImport.parse("Two-card deck\nMountain\nMountain").get(0);
        assertEquals("Two-card deck", b.name());
        assertEquals(List.of("Mountain", "Mountain"), b.cards());
    }

    @Test
    void oneLineEntrySeparatesCardsWithBars() {
        // "Khan, Vengeful Heart" has a comma but is a card, not a name before one
        Block b = EntryImport.parse("Khan, Vengeful Heart | Sickening Shoal | Bottomless Vault").get(0);
        assertEquals(List.of("Khan, Vengeful Heart", "Sickening Shoal", "Bottomless Vault"), b.cards());
        assertTrue(b.generated());
        assertEquals(List.of(), b.problems());
    }

    @Test
    void oneLineEntryCanStartWithAName() {
        Block b = EntryImport.parse("Sligh, Goblin Guide | 2 Mountain").get(0);
        assertEquals(new Block("Sligh", List.of("Goblin Guide", "Mountain", "Mountain"), "", List.of()), b);
        // a name that's also a card, and a card with a comma after the name
        assertEquals("Burn", EntryImport.parse("Burn, Lightning Bolt|Lightning Bolt|Lightning Bolt").get(0).name());
        Block khan = EntryImport.parse("Khan deck, Khan, Vengeful Heart | Island | Island").get(0);
        assertEquals("Khan deck", khan.name());
        assertEquals(List.of("Khan, Vengeful Heart", "Island", "Island"), khan.cards());
    }

    @Test
    void oneLineEntriesInARowAreSeparateEntries() {
        List<Block> blocks = EntryImport.parse("""
                Goblin Guide | Mountain | Mountain
                Swords, Plains | Plains | Swords to Plowshares
                Sligh
                2 Mountain
                Goblin Guide
                Islands, 3 Island | 
                """);
        assertEquals(4, blocks.size());
        assertEquals("GoblMounMoun", blocks.get(0).name());
        assertEquals("Swords", blocks.get(1).name());
        assertEquals(List.of("Plains", "Plains", "Swords to Plowshares"), blocks.get(1).cards());
        // the block before a one-line entry ends there, blank line or not
        assertEquals(new Block("Sligh", List.of("Mountain", "Mountain", "Goblin Guide"), "", List.of()), blocks.get(2));
        assertEquals(new Block("Islands", List.of("Island", "Island", "Island"), "", List.of()), blocks.get(3));
    }

    @Test
    void oneLineEntryKeepsAnUnknownFirstCardWhole() {
        // no comma has a card after it, so nothing is split off and validation reports the card
        Block b = EntryImport.parse("Sligh, Goblin Guid | Mountain | Mountain").get(0);
        assertEquals(List.of("Sligh, Goblin Guid", "Mountain", "Mountain"), b.cards());
        assertTrue(b.generated());
    }

    @Test
    void oneLineEntryNameWithoutACommaNeedsThreeCardsAfterIt() {
        assertEquals("Sligh", EntryImport.parse("Sligh | Goblin Guide | Mountain | Mountain").get(0).name());
        // a misspelled card stays a card (and fails validation) instead of becoming the name
        Block b = EntryImport.parse("Goblin Guid | Mountain | Mountain").get(0);
        assertEquals(List.of("Goblin Guid", "Mountain", "Mountain"), b.cards());
    }

    @Test
    void exportReadsBackTheSame() {
        List<EntryFile> entries = List.of(
                new EntryFile(DeckList.of("Sligh", "Mountain", "Mountain", "Goblin Guide"), null, null, "fast\nhaste"),
                new EntryFile(DeckList.of("Islands", "Island", "Island", "Island"), null, null, ""));
        List<Block> back = EntryImport.parse(EntryImport.export(entries));
        assertEquals(2, back.size());
        assertEquals(new Block("Sligh", List.of("Mountain", "Mountain", "Goblin Guide"), "fast\nhaste", List.of()), back.get(0));
        assertEquals(new Block("Islands", List.of("Island", "Island", "Island"), "", List.of()), back.get(1));
        assertTrue(EntryImport.parse("\n\n  \n").isEmpty());
    }
}
