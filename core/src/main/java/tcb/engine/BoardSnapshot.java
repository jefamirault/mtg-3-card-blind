package tcb.engine;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.google.common.collect.Multiset;

import forge.game.Game;
import forge.game.card.Card;
import forge.game.card.CounterType;
import forge.game.combat.Combat;
import forge.game.phase.PhaseHandler;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbilityStackInstance;
import forge.game.zone.ZoneType;

/**
 * The visible state of a game at one moment, as plain maps and lists ready for Gson: what an
 * animated replay draws. Players are listed as deck A then deck B; cards keep their Forge id
 * for the whole game, so a viewer can follow a card from zone to zone.
 */
public final class BoardSnapshot {
    private BoardSnapshot() {
    }

    private static final ZoneType[] ZONES = {ZoneType.Hand, ZoneType.Battlefield, ZoneType.Graveyard, ZoneType.Exile};

    /** The game's state now. Turns are as Forge counts them ({@code forgeTurn}); callers convert them for display. */
    public static Map<String, Object> of(Game game) {
        Map<String, Object> m = new LinkedHashMap<>();
        PhaseHandler ph = game.getPhaseHandler();
        m.put("forgeTurn", ph.getTurn());
        Player active = ph.getPlayerTurn();
        m.put("active", active == null ? null : active.getName());
        m.put("phase", ph.getPhase() == null ? "" : phaseName(ph.getPhase()));
        List<Map<String, Object>> players = new ArrayList<>();
        Combat combat = game.getCombat();
        for (String name : List.of("A", "B")) {
            Player p = game.getRegisteredPlayers().stream().filter(x -> x.getName().equals(name)).findFirst().orElse(null);
            if (p == null) {
                continue;
            }
            Map<String, Object> pm = new LinkedHashMap<>();
            pm.put("name", name);
            pm.put("life", p.getLife());
            pm.put("library", p.getCardsIn(ZoneType.Library).size());
            for (ZoneType zone : ZONES) {
                List<Map<String, Object>> cards = new ArrayList<>();
                for (Card c : p.getCardsIn(zone)) {
                    cards.add(card(c, zone == ZoneType.Battlefield ? combat : null));
                }
                pm.put(zone.name().toLowerCase(), cards);
            }
            players.add(pm);
        }
        m.put("players", players);
        List<Map<String, Object>> stack = new ArrayList<>();
        for (SpellAbilityStackInstance si : game.getStack()) {
            Map<String, Object> sm = new LinkedHashMap<>();
            Card source = si.getSourceCard();
            sm.put("id", source == null ? null : source.getId());
            sm.put("name", source == null ? "" : source.getName());
            sm.put("controller", si.getActivatingPlayer() == null ? null : si.getActivatingPlayer().getName());
            sm.put("text", si.getStackDescription());
            stack.add(sm);
        }
        m.put("stack", stack);
        return m;
    }

    private static Map<String, Object> card(Card c, Combat combat) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", c.getId());
        m.put("name", c.isFaceDown() ? "Face-down card" : c.getName());
        if (c.isToken()) {
            m.put("token", true);
        }
        if (c.isLand()) {
            m.put("land", true);
        }
        if (c.isTapped()) {
            m.put("tapped", true);
        }
        if (c.isCreature() && c.isInPlay()) {
            m.put("power", c.getNetPower());
            m.put("toughness", c.getNetToughness());
            if (c.getDamage() > 0) {
                m.put("damage", c.getDamage());
            }
            if (c.isSick()) {
                m.put("sick", true);
            }
        }
        Map<String, Integer> cm = new LinkedHashMap<>();
        for (Multiset.Entry<CounterType> e : c.getCounters().entrySet()) {
            cm.put(e.getElement().getName(), e.getCount());
        }
        if (!cm.isEmpty()) {
            m.put("counters", cm);
        }
        if (c.getAttachedTo() != null) {
            m.put("attachedTo", c.getAttachedTo().getId());
        }
        if (combat != null) {
            if (combat.isAttacking(c)) {
                m.put("attacking", true);
            }
            if (combat.isBlocking(c)) {
                List<Integer> blocked = new ArrayList<>();
                for (Card attacker : combat.getAttackers()) {
                    if (combat.isBlocking(c, attacker)) {
                        blocked.add(attacker.getId());
                    }
                }
                m.put("blocking", blocked);
            }
        }
        return m;
    }

    private static String phaseName(PhaseType phase) {
        return switch (phase) {
            case UNTAP -> "Untap";
            case UPKEEP -> "Upkeep";
            case DRAW -> "Draw";
            case MAIN1 -> "Main phase 1";
            case COMBAT_BEGIN -> "Beginning of combat";
            case COMBAT_DECLARE_ATTACKERS -> "Declare attackers";
            case COMBAT_DECLARE_BLOCKERS -> "Declare blockers";
            case COMBAT_FIRST_STRIKE_DAMAGE -> "First-strike damage";
            case COMBAT_DAMAGE -> "Combat damage";
            case COMBAT_END -> "End of combat";
            case MAIN2 -> "Main phase 2";
            case END_OF_TURN -> "End step";
            case CLEANUP -> "Cleanup";
        };
    }
}
