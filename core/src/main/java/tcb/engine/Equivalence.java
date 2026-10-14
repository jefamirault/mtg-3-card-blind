package tcb.engine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import forge.game.Game;
import forge.game.GameEntity;
import forge.game.GameObject;
import forge.game.card.Card;
import forge.game.combat.Combat;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.spellability.SpellAbilityStackInstance;
import forge.game.zone.ZoneType;

/**
 * Tells interchangeable game objects apart from distinct ones, so the search can treat "attack
 * with Memnite #1" and "attack with Memnite #2" as one option when the two Memnites are copies in
 * the same state. Swapping two cards with the same {@link #key} doesn't change the game.
 *
 * Deliberately conservative: a card that anything else refers to (attachments, remembered or
 * imprinted cards, exiled-with, pairing, blocks, targets on the stack, ...) keeps its own id.
 */
final class Equivalence {
    private final Game game;
    private final Set<Card> referenced = new HashSet<>();
    private final boolean everythingDistinct;

    Equivalence(Game game) {
        this(game, false);
    }

    /** No two objects are interchangeable: every option stays separate. */
    static Equivalence none(Game game) {
        return new Equivalence(game, true);
    }

    private Equivalence(Game game, boolean everythingDistinct) {
        this.game = game;
        this.everythingDistinct = everythingDistinct;
        if (everythingDistinct) {
            return;
        }
        for (Card c : game.getCardsInGame()) {
            noteReferences(c);
        }
        for (Card c : game.getCardsIn(ZoneType.Command)) {
            noteReferences(c);
        }
        for (SpellAbilityStackInstance si : game.getStack()) {
            referenced.add(si.getSourceCard());
            for (SpellAbility sa = si.getSpellAbility(); sa != null; sa = sa.getSubAbility()) {
                for (GameObject o : sa.getTargets()) {
                    if (o instanceof Card t) {
                        referenced.add(t);
                    }
                }
            }
        }
    }

    private void noteReferences(Card c) {
        if (c.getExiledWith() != null) {
            referenced.add(c.getExiledWith());
        }
        for (Object o : c.getRemembered()) {
            if (o instanceof Card r) {
                referenced.add(r);
            }
        }
        referenced.addAll(c.getImprintedCards());
        referenced.addAll(c.getChosenCards());
        referenced.addAll(c.getEncodedCards());
        referenced.addAll(c.getMustBlockCards());
        referenced.addAll(c.getGainControlTargets());
        if (c.getPairedWith() != null) {
            referenced.add(c);
            referenced.add(c.getPairedWith());
        }
        if (c.getHaunting() != null) {
            referenced.add(c.getHaunting());
        }
        if (c.isAttachedToEntity() || c.hasCardAttachments()) {
            referenced.add(c);
        }
    }

    /** Equal keys mean the objects are interchangeable. */
    String key(GameObject o) {
        if (o instanceof Card c) {
            return key(c);
        }
        if (o instanceof Player p) {
            return "player " + game.getPlayers().indexOf(p);
        }
        if (o instanceof SpellAbility sa) {
            return "spell " + sa.getId() + " of #" + sa.getHostCard().getId();
        }
        return o.getClass().getSimpleName() + "@" + System.identityHashCode(o);
    }

    String key(Card c) {
        Combat combat = game.getCombat();
        if (everythingDistinct || referenced.contains(c) || (combat != null && (combat.isBlocking(c) || combat.isBlocked(c)))) {
            return "#" + c.getId();
        }
        return state(game, c);
    }

    /**
     * A card's current state without its id: zone, control, and on the battlefield its status and
     * current characteristics (which include temporary changes, e.g. a land animated until end of
     * turn, that Forge's game-state dump leaves out).
     */
    static String state(Game game, Card c) {
        Combat combat = game.getCombat();
        StringBuilder sb = new StringBuilder(c.getName());
        sb.append('|').append(c.getZone() == null ? "?" : c.getZone().getZoneType())
          .append("|ctl ").append(game.getPlayers().indexOf(c.getController()))
          .append("|own ").append(game.getPlayers().indexOf(c.getOwner()));
        if (c.isInZone(ZoneType.Battlefield)) {
            sb.append(c.isTapped() ? "|tapped" : "")
              .append(c.isSick() ? "|sick" : "")
              .append(c.enteredThisTurn() ? "|new" : "")
              .append(c.isPhasedOut() ? "|phased out" : "")
              .append("|dmg ").append(c.getDamage())
              .append("|pt ").append(c.getNetPower()).append('/').append(c.getNetToughness())
              .append("|type ").append(c.getType())
              .append("|color ").append(c.getColor())
              .append("|kw ").append(keywords(c));
            if (combat != null && combat.isAttacking(c)) {
                GameEntity defender = combat.getDefenderByAttacker(c);
                sb.append("|attacking ").append(defender instanceof Card d ? "#" + d.getId() : String.valueOf(defender));
            }
        }
        sb.append(c.isToken() ? "|token" : "")
          .append(c.isFaceDown() ? "|face down" : "")
          .append('|').append(c.getCurrentStateName())
          .append("|counters ").append(c.getCounters());
        return sb.toString();
    }

    private static String keywords(Card c) {
        List<String> kws = new ArrayList<>();
        c.getKeywords().forEach(k -> kws.add(k.toString()));
        Collections.sort(kws);
        return String.join(",", kws);
    }
}
