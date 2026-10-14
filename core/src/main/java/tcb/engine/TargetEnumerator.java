package tcb.engine;

import java.util.ArrayList;
import java.util.List;

import forge.game.GameObject;
import forge.game.card.Card;
import forge.game.spellability.SpellAbility;
import forge.game.spellability.SpellAbilityCondition;
import forge.game.spellability.SpellAbilityStackInstance;
import forge.game.spellability.TargetRestrictions;
import forge.game.zone.ZoneType;

/**
 * Enumerates every legal target assignment for a spell or ability, including spells and abilities
 * on the stack (which Forge's AI target selector doesn't consider, so counterspells never fire).
 *
 * An assignment holds one list of targets per targeting part of the ability chain, e.g. "destroy
 * target creature, then deal 2 damage to target player" has two parts.
 */
final class TargetEnumerator {
    private TargetEnumerator() {
    }

    /** Targeting parts of the chain whose conditions currently hold. */
    static List<SpellAbility> targetingParts(SpellAbility sa) {
        List<SpellAbility> parts = new ArrayList<>();
        for (SpellAbility s = sa; s != null; s = s.getSubAbility()) {
            if (s.usesTargeting() && conditionsMet(s)) {
                parts.add(s);
            }
        }
        return parts;
    }

    private static boolean conditionsMet(SpellAbility s) {
        SpellAbilityCondition conditions = s.getConditions();
        return conditions == null || conditions.areMet(s);
    }

    /** All assignments (up to {@code cap}); leaves the ability's targets cleared. */
    static List<List<List<GameObject>>> enumerate(SpellAbility sa, int cap) {
        List<SpellAbility> parts = targetingParts(sa);
        List<List<List<GameObject>>> out = new ArrayList<>();
        recurse(parts, 0, new ArrayList<>(), out, cap);
        clear(parts);
        return out;
    }

    private static void recurse(List<SpellAbility> parts, int i, List<List<GameObject>> chosen,
                                List<List<List<GameObject>>> out, int cap) {
        if (out.size() >= cap) {
            return;
        }
        if (i == parts.size()) {
            out.add(new ArrayList<>(chosen));
            return;
        }
        SpellAbility part = parts.get(i);
        part.resetTargets();
        List<GameObject> candidates = candidates(part);
        int min = part.getMinTargets();
        int max = Math.min(part.getMaxTargets(), candidates.size());
        for (List<GameObject> subset : subsets(candidates, min, max, cap)) {
            part.resetTargets();
            for (GameObject o : subset) {
                part.getTargets().add(o);
            }
            chosen.add(subset);
            recurse(parts, i + 1, chosen, out, cap);
            chosen.remove(chosen.size() - 1);
            if (out.size() >= cap) {
                return;
            }
        }
        part.resetTargets();
    }

    private static List<GameObject> candidates(SpellAbility part) {
        TargetRestrictions tgt = part.getTargetRestrictions();
        List<GameObject> candidates = new ArrayList<>(tgt.getAllCandidates(part));
        if (tgt.getZone().contains(ZoneType.Stack)) {
            for (SpellAbilityStackInstance si : part.getHostCard().getGame().getStack()) {
                SpellAbility onStack = si.getSpellAbility();
                if (part.canTargetSpellAbility(onStack) && !candidates.contains(onStack)) {
                    candidates.add(onStack);
                }
            }
        }
        return candidates;
    }

    private static List<List<GameObject>> subsets(List<GameObject> items, int min, int max, int cap) {
        List<List<GameObject>> out = new ArrayList<>();
        if (items.size() > 20) {
            // Too many to enumerate subsets; single targets only.
            for (GameObject o : items) {
                out.add(List.of(o));
            }
            return out;
        }
        for (int size = Math.max(min, 0); size <= max; size++) {
            if (size == 0 && min > 0) {
                continue;
            }
            collect(items, size, 0, new ArrayList<>(), out, cap);
        }
        return out;
    }

    private static void collect(List<GameObject> items, int size, int from, List<GameObject> cur,
                                List<List<GameObject>> out, int cap) {
        if (out.size() >= cap) {
            return;
        }
        if (cur.size() == size) {
            out.add(List.copyOf(cur));
            return;
        }
        for (int k = from; k < items.size(); k++) {
            cur.add(items.get(k));
            collect(items, size, k + 1, cur, out, cap);
            cur.remove(cur.size() - 1);
        }
    }

    /** Sets the ability's targets to {@code assignment}. */
    static void apply(SpellAbility sa, List<List<GameObject>> assignment) {
        List<SpellAbility> parts = targetingParts(sa);
        if (parts.size() != assignment.size()) {
            throw new IllegalStateException("target parts changed for " + sa);
        }
        for (int i = 0; i < parts.size(); i++) {
            SpellAbility part = parts.get(i);
            part.resetTargets();
            for (GameObject o : assignment.get(i)) {
                part.getTargets().add(o);
            }
        }
    }

    static void clear(List<SpellAbility> parts) {
        for (SpellAbility part : parts) {
            part.resetTargets();
        }
    }

    static String describe(List<List<GameObject>> assignment) {
        List<String> parts = new ArrayList<>();
        for (List<GameObject> targets : assignment) {
            List<String> names = new ArrayList<>();
            for (GameObject o : targets) {
                names.add(describe(o));
            }
            parts.add(names.isEmpty() ? "no target" : String.join(" & ", names));
        }
        return String.join("; ", parts);
    }

    private static String describe(GameObject o) {
        if (o instanceof Card c) {
            return c.getName() + "#" + c.getId() + " (" + c.getController() + (c.isTapped() ? ", tapped" : "") + ")";
        }
        if (o instanceof SpellAbility s) {
            return "spell " + s.getHostCard().getName() + "#" + s.getHostCard().getId();
        }
        return o.toString();
    }
}
