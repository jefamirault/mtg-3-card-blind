package tcb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import tcb.deck.DeckList;
import tcb.deck.VintageValidator;

class VintageValidatorTest {

    @Test
    void parsesCountsAndComments() {
        DeckList deck = DeckList.parse("sligh", "# my deck\n2 Mountain\nGoblin Guide  # one-drop\n");
        assertEquals(List.of("Mountain", "Mountain", "Goblin Guide"), deck.cards());
    }

    @Test
    void acceptsBasicLands() {
        assertTrue(VintageValidator.problems(DeckList.of("lands", "Mountain", "Mountain", "Mountain")).isEmpty());
    }

    @Test
    void acceptsOneRestrictedCard() {
        assertTrue(VintageValidator.problems(DeckList.of("lotus", "Black Lotus", "Swamp", "Dark Ritual")).isEmpty());
    }

    @Test
    void acceptsCopiesOfRestrictedCard() {
        // 3CB ignores the restricted list (issue #13)
        assertTrue(VintageValidator.problems(DeckList.of("lotus", "Black Lotus", "Black Lotus", "Black Lotus")).isEmpty());
    }

    @Test
    void acceptsCopiesOfNonbasicCard() {
        assertTrue(VintageValidator.problems(DeckList.of("guides", "Goblin Guide", "Goblin Guide", "Goblin Guide")).isEmpty());
    }

    @Test
    void rejectsBannedCard() {
        assertEquals(List.of("not Vintage-legal: Chaos Orb is banned"),
                VintageValidator.problems(DeckList.of("orb", "Chaos Orb", "Mountain", "Mountain")));
    }

    @Test
    void rejectsUnCards() {
        // Blacker Lotus has a (acorn) Secret Lair printing, which Forge's format file would accept.
        assertEquals(List.of("not Vintage-legal: Blacker Lotus is only printed as an Un-/acorn card"),
                VintageValidator.problems(DeckList.of("silver", "Blacker Lotus", "Swamp", "Swamp")));
    }

    @Test
    void acceptsNonAcornUnfinityCard() {
        assertTrue(VintageValidator.problems(DeckList.of("unf", "Saw in Half", "Swamp", "Swamp")).isEmpty());
    }

    @Test
    void rejectsArenaRebalancedCard() {
        // Forge has scripts for Arena's rebalanced cards ("A-" names); no paper set prints them.
        assertEquals(List.of("unknown card: A-Alrund's Epiphany"),
                VintageValidator.problems(DeckList.of("alchemy", "A-Alrund's Epiphany", "Island", "Island")));
        // one that a set does list
        assertEquals(List.of("not Vintage-legal: A-Lier, Disciple of the Drowned is an Arena rebalanced card"),
                VintageValidator.problems(DeckList.of("alchemy", "A-Lier, Disciple of the Drowned", "Island", "Island")));
        // the paper card is fine
        assertTrue(VintageValidator.problems(DeckList.of("paper", "Alrund's Epiphany", "Island", "Island")).isEmpty());
    }

    @Test
    void rejectsArenaOnlyCard() {
        assertEquals(List.of("unknown card: Treetop Recluse"),
                VintageValidator.problems(DeckList.of("arena", "Treetop Recluse", "Forest", "Forest")));
        // printed only in a digital set (Alchemy: Innistrad)
        assertEquals(List.of("not Vintage-legal: Key to the Archive isn't printed in a Vintage-legal set (digital-only cards aren't legal)"),
                VintageValidator.problems(DeckList.of("arena", "Key to the Archive", "Forest", "Forest")));
    }

    @Test
    void rejectsWrongSize() {
        assertFalse(VintageValidator.problems(DeckList.of("four", "Island", "Island", "Island", "Island")).isEmpty());
        assertFalse(VintageValidator.problems(DeckList.of("two", "Island", "Island")).isEmpty());
    }

    @Test
    void rejectsUnknownCard() {
        assertFalse(VintageValidator.problems(DeckList.of("typo", "Mountian", "Mountain", "Mountain")).isEmpty());
    }
}
