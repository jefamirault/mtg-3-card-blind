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
    void rejectsTwoCopiesOfRestrictedCard() {
        assertFalse(VintageValidator.problems(DeckList.of("lotus", "Black Lotus", "Black Lotus", "Swamp")).isEmpty());
    }

    @Test
    void rejectsBannedCard() {
        assertFalse(VintageValidator.problems(DeckList.of("orb", "Chaos Orb", "Mountain", "Mountain")).isEmpty());
    }

    @Test
    void rejectsUnCards() {
        // Blacker Lotus has a (acorn) Secret Lair printing, which Forge's format file would accept.
        assertFalse(VintageValidator.problems(DeckList.of("silver", "Blacker Lotus", "Swamp", "Swamp")).isEmpty());
    }

    @Test
    void acceptsNonAcornUnfinityCard() {
        assertTrue(VintageValidator.problems(DeckList.of("unf", "Saw in Half", "Swamp", "Swamp")).isEmpty());
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
