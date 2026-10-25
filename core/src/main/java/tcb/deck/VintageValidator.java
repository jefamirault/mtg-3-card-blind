package tcb.deck;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Pattern;

import forge.deck.CardPool;
import forge.deck.Deck;
import forge.deck.DeckSection;
import forge.game.GameFormat;
import forge.item.PaperCard;
import forge.model.FModel;
import tcb.engine.ForgeEnv;

/**
 * Checks that an entry is exactly 3 cards, each Vintage-legal (Forge's Vintage sets and banned
 * list). Any number of copies of a card is allowed, restricted cards included: 3CB ignores the
 * restricted list.
 */
public final class VintageValidator {
    public static final int DECK_SIZE = 3;

    private VintageValidator() {
    }

    /** Returns the problems with the deck; empty when legal. */
    public static List<String> problems(DeckList deckList) {
        ForgeEnv.init();
        List<String> problems = new ArrayList<>();
        if (deckList.cards().size() != DECK_SIZE) {
            problems.add("deck has " + deckList.cards().size() + " cards, must have exactly " + DECK_SIZE);
        }
        for (String name : deckList.cards()) {
            if (lookup(name) == null) {
                problems.add("unknown card: " + name);
            }
        }
        if (!problems.isEmpty()) {
            return problems;
        }
        GameFormat vintage = FModel.getFormats().getVintage();
        for (String name : new LinkedHashSet<>(deckList.cards())) {
            if (!hasTournamentLegalPrinting(vintage, name)) {
                problems.add("not Vintage-legal: " + name + " is only printed as an Un-/acorn card");
            }
        }
        if (!problems.isEmpty()) {
            return problems;
        }
        // Card by card rather than Forge's getDeckConformanceProblem, which also enforces the
        // restricted list.
        for (String name : new LinkedHashSet<>(deckList.cards())) {
            if (!vintage.getFilterRules().test(lookup(name))) {
                problems.add("not Vintage-legal: " + name
                        + (vintage.getBannedCardNames().contains(name) ? " is banned" : ""));
            }
        }
        return problems;
    }

    public static void requireLegal(DeckList deckList) {
        List<String> problems = problems(deckList);
        if (!problems.isEmpty()) {
            throw new IllegalArgumentException(deckList.name() + ": " + String.join("; ", problems));
        }
    }

    /** Acorn/"funny" printings have collector numbers like F869 (Forge's own convention). */
    private static final Pattern FUNNY_NUMBER = Pattern.compile("^F★?\\d+★?");

    /**
     * Forge's Vintage format accepts any card printed in an allowed set, which lets in Un-cards
     * reprinted with acorn stamps (e.g. Blacker Lotus in Secret Lair). Require a printing in an
     * allowed set that isn't an acorn printing.
     */
    public static boolean hasTournamentLegalPrinting(GameFormat format, String name) {
        List<String> sets = format.getAllowedSetCodes();
        for (PaperCard printing : FModel.getMagicDb().getCommonCards().getAllCards(name)) {
            String number = printing.getCollectorNumber();
            boolean acorn = number != null && FUNNY_NUMBER.matcher(number).matches();
            if (!acorn && (sets.isEmpty() || sets.contains(printing.getEdition()))) {
                return true;
            }
        }
        return false;
    }

    static PaperCard lookup(String name) {
        return FModel.getMagicDb().getCommonCards().getCard(name);
    }

    /** Builds the Forge deck used to start games. */
    public static Deck toForgeDeck(DeckList deckList) {
        ForgeEnv.init();
        Deck deck = new Deck(deckList.name());
        CardPool main = deck.getOrCreate(DeckSection.Main);
        for (String name : deckList.cards()) {
            PaperCard card = lookup(name);
            if (card == null) {
                throw new IllegalArgumentException("unknown card: " + name);
            }
            main.add(card);
        }
        return deck;
    }
}
