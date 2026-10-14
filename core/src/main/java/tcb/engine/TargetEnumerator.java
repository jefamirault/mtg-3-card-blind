package tcb.engine;

import java.util.ArrayList;
import java.util.Collections;
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
 * on the stack (which Forge's AI target selector doesn't consider, so counterspells never fire),
 * and every way to divide an amount "as you choose" among the targets.
 *
 * An assignment holds one {@link Part} per targeting part of the ability chain, e.g. "destroy
 * target creature, then deal 2 damage to target player" has two parts.
 */
final class TargetEnumerator {
    private TargetEnumerator() {
    }

    /**
     * The targets of one targeting part, and for "divided as you choose" parts the amount each
     * target gets (same order as {@code targets}; null otherwise).
     */
    record Part(List<GameObject> targets, int[] division) {
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
    static List<List<Part>> enumerate(SpellAbility sa, int cap) {
        List<SpellAbility> parts = targetingParts(sa);
        List<List<Part>> out = new ArrayList<>();
        recurse(parts, 0, new ArrayList<>(), out, cap);
        clear(parts);
        return out;
    }

    /** True if every targeting part of the chain has a valid number of targets. */
    static boolean valid(SpellAbility sa) {
        for (SpellAbility part : targetingParts(sa)) {
            if (!part.isTargetNumberValid()) {
                return false;
            }
        }
        return true;
    }

    private static void recurse(List<SpellAbility> parts, int i, List<Part> chosen, List<List<Part>> out, int cap) {
        if (out.size() >= cap) {
            return;
        }
        if (i == parts.size()) {
            out.add(new ArrayList<>(chosen));
            return;
        }
        SpellAbility part = parts.get(i);
        part.clearTargets();
        List<GameObject> candidates = candidates(part);
        int min = part.getMinTargets();
        int max = Math.min(part.getMaxTargets(), candidates.size());
        for (List<GameObject> subset : subsets(candidates, min, max, cap)) {
            for (int[] division : divisions(part, subset.size())) {
                Part p = new Part(subset, division);
                apply(part, p);
                chosen.add(p);
                recurse(parts, i + 1, chosen, out, cap);
                chosen.remove(chosen.size() - 1);
                if (out.size() >= cap) {
                    return;
                }
            }
        }
        part.clearTargets();
    }

    /**
     * Ways to divide a "divided as you choose" amount among {@code n} targets, at least 1 each;
     * a single null (no division) for other parts.
     */
    private static List<int[]> divisions(SpellAbility part, int n) {
        List<int[]> out = new ArrayList<>();
        if (!part.isDividedAsYouChoose() || n == 0) {
            out.add(null);
            return out;
        }
        part.clearTargets(); // also recomputes the amount to divide
        int total = part.getStillToDivide();
        if (total < n) {
            return out; // can't give every target at least 1
        }
        compose(total, new int[n], 0, out);
        return out;
    }

    private static void compose(int left, int[] cur, int i, List<int[]> out) {
        if (i == cur.length - 1) {
            cur[i] = left;
            out.add(cur.clone());
            return;
        }
        for (int x = 1; x <= left - (cur.length - 1 - i); x++) {
            cur[i] = x;
            compose(left - x, cur, i + 1, out);
        }
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

    /** Sets the ability's targets (and divisions) to {@code assignment}. */
    static void apply(SpellAbility sa, List<Part> assignment) {
        List<SpellAbility> parts = targetingParts(sa);
        if (parts.size() != assignment.size()) {
            throw new IllegalStateException("target parts changed for " + sa);
        }
        for (int i = 0; i < parts.size(); i++) {
            apply(parts.get(i), assignment.get(i));
        }
    }

    private static void apply(SpellAbility part, Part p) {
        part.clearTargets();
        for (int k = 0; k < p.targets().size(); k++) {
            GameObject o = p.targets().get(k);
            part.getTargets().add(o);
            if (p.division() != null) {
                part.addDividedAllocation(o, p.division()[k]);
            }
        }
    }

    static void clear(List<SpellAbility> parts) {
        for (SpellAbility part : parts) {
            part.clearTargets();
        }
    }

    /** Equal keys mean the assignments are interchangeable: the same targets up to {@link Equivalence}. */
    static String key(List<Part> assignment, Equivalence eq) {
        List<String> parts = new ArrayList<>();
        for (Part p : assignment) {
            List<String> keys = new ArrayList<>();
            for (int k = 0; k < p.targets().size(); k++) {
                keys.add(eq.key(p.targets().get(k)) + (p.division() == null ? "" : " x" + p.division()[k]));
            }
            Collections.sort(keys);
            parts.add(String.join(" & ", keys));
        }
        return String.join("; ", parts);
    }

    static String describe(List<Part> assignment) {
        List<String> parts = new ArrayList<>();
        for (Part p : assignment) {
            List<String> names = new ArrayList<>();
            for (int k = 0; k < p.targets().size(); k++) {
                names.add(describe(p.targets().get(k)) + (p.division() == null ? "" : ": " + p.division()[k]));
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
