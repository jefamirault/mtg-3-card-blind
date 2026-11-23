package tcb.engine;

import forge.game.ability.ApiType;
import forge.game.card.Card;
import forge.game.cost.CostPayLife;
import forge.game.cost.CostPutCounter;
import forge.game.spellability.SpellAbility;
import forge.game.trigger.Trigger;
import forge.game.trigger.TriggerType;

/**
 * Which mana abilities are worth activating at priority, rather than only while paying for something.
 *
 * Sid's rule: a player who taps a permanent for mana and spends none of it, so all of it is lost at
 * the end of the step, gains nothing the line without the tap doesn't have. The exceptions are
 * abilities that do something besides making mana ({@link #hasSideEffect}). So the solver offers
 * only those at priority; every other mana source is tapped only to pay a cost, where its mana is
 * spent. (Floating mana in response to something is the one use this leaves out.)
 */
final class ManaTaps {
    private ManaTaps() {
    }

    /**
     * Whether a mana ability does something besides making mana: it puts a counter on its own
     * permanent, deals damage, has a life payment, or makes a player gain life. A "whenever this
     * becomes tapped" trigger on the permanent with such an effect (City of Brass) counts too.
     */
    static boolean hasSideEffect(SpellAbility sa) {
        if (sa.getPayCosts() != null && (sa.getPayCosts().hasSpecificCostType(CostPayLife.class)
                || sa.getPayCosts().hasSpecificCostType(CostPutCounter.class))) {
            return true;
        }
        if (chainHasSideEffect(sa)) {
            return true;
        }
        Card host = sa.getHostCard();
        for (Trigger t : host.getTriggers()) {
            boolean onTap = t.getMode() == TriggerType.Taps || t.getMode() == TriggerType.TapsForMana;
            if (onTap && "Card.Self".equals(t.getParam("ValidCard")) && chainHasSideEffect(t.ensureAbility())) {
                return true;
            }
        }
        return false;
    }

    private static boolean chainHasSideEffect(SpellAbility first) {
        for (SpellAbility a = first; a != null; a = a.getSubAbility()) {
            ApiType api = a.getApi();
            if (api == ApiType.DealDamage || api == ApiType.GainLife) {
                return true;
            }
            if (api == ApiType.PutCounter && (!a.hasParam("Defined") || "Self".equals(a.getParam("Defined")))) {
                return true;
            }
        }
        return false;
    }
}
