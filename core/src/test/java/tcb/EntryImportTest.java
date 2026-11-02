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
