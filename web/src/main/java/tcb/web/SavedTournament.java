package tcb.web;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.function.Predicate;

import com.google.gson.JsonObject;

import forge.game.GameFormat;
import forge.item.PaperCard;
import forge.model.FModel;
import tcb.deck.DeckList;
import tcb.deck.VintageValidator;
import tcb.solve.GameReplay;
import tcb.solve.Solver;
import tcb.solve.Turns;

/**
 * Reading a saved tournament ({@code history/<id>.json}): its summary for the list, and a game's
 * replay. Shared by the web server and the public-site export ({@link SiteExport}).
 */
final class SavedTournament {
    private SavedTournament() {
    }

    /** The list's summary of a saved tournament. */
    static Map<String, Object> summary(JsonObject saved) {
        JsonObject results = saved.getAsJsonObject("results");
        List<String> entries = new ArrayList<>();
        results.getAsJsonArray("entries").forEach(e -> entries.add(e.getAsJsonObject().get("name").getAsString()));
        // Each entry's record, without points: the page ranks them by its own scoring.
        List<Map<String, Object>> records = new ArrayList<>();
        for (var s : results.getAsJsonArray("standings")) {
            JsonObject o = s.getAsJsonObject();
            records.add(Map.of("name", o.get("name").getAsString(), "wins", o.get("wins").getAsDouble(),
                    "draws", o.get("draws").getAsDouble(), "losses", o.get("losses").getAsDouble()));
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", saved.get("id").getAsString());
        m.put("finished", saved.get("finished").getAsString());
        m.put("seconds", saved.get("seconds").getAsDouble());
        m.put("entries", entries);
        m.put("games", results.getAsJsonArray("games").size());
        m.put("notPlayed", results.has("notPlayed") ? results.getAsJsonArray("notPlayed").size() : 0);
        m.put("standings", records);
        return m;
    }

    static int games(JsonObject saved) {
        return saved.getAsJsonObject("results").getAsJsonArray("games").size();
    }

    /** Whether game {@code k} has moves to watch: a search stopped or out of budget saves only a note. */
    static boolean hasLine(JsonObject saved, int k) {
        JsonObject g = game(saved, k);
        for (var l : g.getAsJsonArray("line")) {
            String s = l.getAsString();
            if (!(s.startsWith("(") && s.endsWith(")"))) {
                return true;
            }
        }
        return false;
    }

    /**
     * The board at every step of game {@code k}'s saved line (see {@link GameReplay}). Throws, with a
     * {@link tcb.engine.NondeterministicReplayException} as the cause, when the engine no longer
     * plays the line the same way.
     */
    static Map<String, Object> replay(JsonObject saved, int k) {
        JsonObject results = saved.getAsJsonObject("results");
        JsonObject g = game(saved, k);
        DeckList a = deck(results, g.get("a").getAsString());
        DeckList b = deck(results, g.get("b").getAsString());
        int startingPlayer = g.get("onThePlay").getAsString().equals(a.name()) ? 0 : 1;
        List<String> line = new ArrayList<>();
        g.getAsJsonArray("line").forEach(l -> line.add(l.getAsString()));
        // Older saves have maxTurns (Forge turns); newer ones turns (turn cycles).
        JsonObject o = saved.has("options") ? saved.getAsJsonObject("options") : new JsonObject();
        int turnLimit = o.has("turns") ? Turns.forgeTurns(o.get("turns").getAsInt())
                : o.has("maxTurns") ? o.get("maxTurns").getAsInt() : Solver.Options.defaults().maxTurns();
        // A slow win found past the limit was solved, and has to be replayed, with a higher one.
        if (g.has("provenTurns")) {
            turnLimit = Math.max(turnLimit, Turns.forgeTurns(g.get("provenTurns").getAsInt()));
        }
        return GameReplay.replay(a, b, startingPlayer, line, turnLimit);
    }

    private static JsonObject game(JsonObject saved, int k) {
        return saved.getAsJsonObject("results").getAsJsonArray("games").get(k).getAsJsonObject();
    }

    private static DeckList deck(JsonObject results, String name) {
        for (var e : results.getAsJsonArray("entries")) {
            JsonObject entry = e.getAsJsonObject();
            if (entry.get("name").getAsString().equals(name)) {
                List<String> cards = new ArrayList<>();
                entry.getAsJsonArray("cards").forEach(c -> cards.add(c.getAsString()));
                return new DeckList(name, cards);
            }
        }
        throw new IllegalStateException("saved tournament has no entry called " + name);
    }

    /** Vintage-legal card names, sorted ignoring case. Needs {@link tcb.engine.ForgeEnv#init()}. */
    static List<String> vintageCardNames() {
        GameFormat vintage = FModel.getFormats().getVintage();
        Predicate<PaperCard> legal = vintage.getFilterRules();
        TreeSet<String> names = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (PaperCard card : FModel.getMagicDb().getCommonCards().getUniqueCards()) {
            if (legal.test(card) && !vintage.getBannedCardNames().contains(card.getName())
                    && VintageValidator.hasTournamentLegalPrinting(vintage, card.getName())) {
                names.add(card.getName());
            }
        }
        return new ArrayList<>(names);
    }
}
