package tcb.solve;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import tcb.deck.DeckList;
import tcb.solve.Tournament.Results;
import tcb.solve.Tournament.Standing;

/** Tournament results as plain maps/lists, ready for Gson. Shared by the CLI and web server. */
public final class ResultsJson {
    private ResultsJson() {
    }

    public static Map<String, Object> toJson(Results r) {
        Map<String, Object> root = new LinkedHashMap<>();
        List<Map<String, Object>> entries = new ArrayList<>();
        for (DeckList d : r.entries()) {
            entries.add(Map.of("name", d.name(), "cards", d.cards()));
        }
        root.put("entries", entries);
        // matrix[i][j]: points entry i scored against entry j over both games (null on the diagonal)
        List<List<Double>> matrix = new ArrayList<>();
        for (int i = 0; i < r.entries().size(); i++) {
            List<Double> row = new ArrayList<>();
            for (int j = 0; j < r.entries().size(); j++) {
                row.add(i == j ? null : r.pointsAgainst(i, j));
            }
            matrix.add(row);
        }
        root.put("matrix", matrix);
        List<Map<String, Object>> standings = new ArrayList<>();
        for (Standing s : r.standings()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", s.deck().name());
            m.put("cards", s.deck().cards());
            m.put("points", s.points());
            m.put("wins", s.wins());
            m.put("draws", s.draws());
            m.put("losses", s.losses());
            standings.add(m);
        }
        root.put("standings", standings);
        List<Map<String, Object>> games = new ArrayList<>();
        for (Tournament.Game g : r.games()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("a", r.entries().get(g.a()).name());
            m.put("b", r.entries().get(g.b()).name());
            m.put("onThePlay", r.entries().get(g.startingPlayer() == 0 ? g.a() : g.b()).name());
            m.put("pWinA", g.result().pWin());
            m.put("pDraw", g.result().pDraw());
            m.put("pWinB", g.result().pLoss());
            m.put("complete", g.result().complete());
            m.put("provenTurnLimit", g.result().provenTurnLimit());
            m.put("nodes", g.result().nodes());
            m.put("seconds", g.result().elapsed().toMillis() / 1000.0);
            m.put("line", g.result().principalVariation());
            games.add(m);
        }
        root.put("games", games);
        return root;
    }
}
