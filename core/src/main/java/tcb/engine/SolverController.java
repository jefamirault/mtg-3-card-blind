package tcb.engine;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import forge.ai.AiCache;
import forge.ai.ComputerUtil;
import forge.ai.ComputerUtilAbility;
import forge.ai.ComputerUtilCard;
import forge.ai.ComputerUtilCombat;
import forge.ai.ComputerUtilCost;
import forge.ai.ComputerUtilMana;
import forge.ai.PlayerControllerAi;
import forge.game.Game;
import forge.game.GameEntity;
import forge.game.GameObject;
import forge.game.GameState;
import forge.game.ability.AbilityUtils;
import forge.game.ability.ApiType;
import forge.game.ability.effects.CharmEffect;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.card.mana.ManaCost;
import forge.game.card.CardCollectionView;
import forge.game.cost.Cost;
import forge.game.cost.CostPartMana;
import forge.game.keyword.Keyword;
import forge.game.mana.ManaConversionMatrix;
import forge.game.combat.Combat;
import forge.game.combat.CombatUtil;
import forge.game.phase.PhaseType;
import forge.game.player.DelayedReveal;
import forge.game.player.Player;
import forge.game.player.PlayerActionConfirmMode;
import forge.game.spellability.AbilitySub;
import forge.game.spellability.SpellAbility;
import forge.game.trigger.WrappedAbility;
import forge.game.zone.ZoneType;
import forge.util.collect.FCollectionView;

/**
 * A Forge player controller whose decisions all come from a {@link DecisionTape}: every real choice
 * (what to cast with which X and targets, trigger targets and order, mana payment, attacks,
 * blocks, combat damage, opening-hand actions, yes/no prompts, numbers, modes, card picks)
 * becomes a branch point of the game tree.
 *
 * Choices not yet enumerated here fall back to Forge's AI heuristics (inherited from
 * {@link PlayerControllerAi}); see README "Known limitations".
 */
public class SolverController extends PlayerControllerAi {
    /** Upper bound on options enumerated for a single decision; larger choices fall back to the AI. */
    static final int MAX_OPTIONS = 64;
    /** Cap on target assignments listed for one spell or ability (beyond it, the rest are dropped). */
    static final int MAX_TARGETINGS = 512;

    private final DecisionTape tape;
    private final int startingPlayer;
    /** Don't merge interchangeable options or skip repeated windows (for checking those reductions). */
    private final boolean exhaustive;

    public SolverController(Game game, Player player, forge.LobbyPlayer lobbyPlayer, DecisionTape tape, int startingPlayer,
                            boolean exhaustive) {
        super(game, player, lobbyPlayer);
        this.tape = tape;
        this.startingPlayer = startingPlayer;
        this.exhaustive = exhaustive;
    }

    private Equivalence equivalence() {
        return exhaustive ? Equivalence.none(getGame()) : new Equivalence(getGame());
    }

    private int me() {
        return getGame().getPlayers().indexOf(player);
    }

    private int decide(String kind, List<String> labels, boolean transposable) {
        return tape.decide(me(), kind, labels, getGame().getPhaseHandler().getTurn(),
                () -> transposable ? position(kind, labels) : null);
    }

    // ---------------------------------------------------------------- game start

    @Override
    public Player chooseStartingPlayer(boolean isFirstGame) {
        return getGame().getPlayers().get(startingPlayer);
    }

    @Override
    public boolean mulliganKeepHand(Player firstPlayer, int cardsToReturn) {
        return true; // 3CB: no mulligans
    }

    /** Leylines, Gemstone Caverns, Chancellors: which to use (in the order Forge lists them). */
    @Override
    public List<SpellAbility> chooseSaToActivateFromOpeningHand(List<SpellAbility> usable) {
        if (usable.size() > 6) {
            return super.chooseSaToActivateFromOpeningHand(usable);
        }
        Equivalence eq = equivalence();
        Map<String, List<SpellAbility>> subsets = new LinkedHashMap<>();
        for (int mask = (1 << usable.size()) - 1; mask >= 0; mask--) {
            List<SpellAbility> subset = new ArrayList<>();
            List<String> keys = new ArrayList<>();
            for (int i = 0; i < usable.size(); i++) {
                if ((mask & (1 << i)) != 0) {
                    subset.add(usable.get(i));
                    keys.add(eq.key(usable.get(i).getHostCard()) + ": " + usable.get(i).getDescription());
                }
            }
            keys.sort(null);
            subsets.putIfAbsent(String.join(" | ", keys), subset);
        }
        List<List<SpellAbility>> options = new ArrayList<>(subsets.values());
        List<String> labels = new ArrayList<>();
        for (List<SpellAbility> subset : options) {
            List<String> names = new ArrayList<>();
            for (SpellAbility sa : subset) {
                names.add(sa.getHostCard().getName());
            }
            String label = "From opening hand: " + (names.isEmpty() ? "nothing" : String.join(", ", names));
            labels.add(labels.contains(label) ? label + " (" + labels.size() + ")" : label);
        }
        return options.get(decide("opening hand", labels, false));
    }

    // ---------------------------------------------------------------- priority

    /** A spell or ability to play, with its X (null if none) and targets (null if untargeted). */
    private record PlayOption(SpellAbility sa, Integer x, List<TargetEnumerator.Part> targets) {
        void prepare() {
            if (x != null) {
                sa.setXManaCostPaid(x);
            }
            if (targets != null) {
                TargetEnumerator.apply(sa, targets);
            }
        }
    }

    /** Largest X offered when casting; bigger values fall back to the largest affordable. */
    static final int MAX_X = 20;

    @Override
    public List<SpellAbility> chooseSpellAbilityToPlay() {
        AiCache.clear(); // as Forge's AI does at each priority decision; the AI fallbacks rely on it
        Game game = getGame();
        int turn = game.getPhaseHandler().getTurn();
        tape.checkTurn(turn);
        if (game.getPhaseHandler().is(PhaseType.MAIN1, player) && game.getStack().isEmpty() && tape.checkpointDue(turn)) {
            tape.checkpoint(position("checkpoint", List.of()));
        }
        PhaseType previous = tape.notePriority(turn, game.getPhaseHandler().getPhase());
        if (!exhaustive && game.getStack().isEmpty() && repeatsEarlierWindow(previous)) {
            return null;
        }
        if (tape.replaysPass(me())) {
            return null;
        }

        // Options that differ only in which of several interchangeable cards they use (e.g. which
        // of three identical Memnites to Putrefy) are one option: keyed by equivalence, not label.
        Equivalence eq = equivalence();
        Map<String, PlayOption> byKey = new LinkedHashMap<>();
        Map<String, String> labelByKey = new LinkedHashMap<>();
        for (SpellAbility sa : candidateAbilities()) {
            for (Integer x : xValues(sa)) {
                if (x != null) {
                    sa.setXManaCostPaid(x);
                }
                String base = describe(sa) + (x == null ? "" : " with X=" + x);
                String baseKey = eq.key(sa.getHostCard()) + "|" + sa.getDescription() + "|" + base;
                if (TargetEnumerator.targetingParts(sa).isEmpty()) {
                    if ((x == null || ComputerUtilCost.canPayCost(sa, player, false))
                            && byKey.putIfAbsent(baseKey, new PlayOption(sa, x, null)) == null) {
                        labelByKey.put(baseKey, base);
                    }
                    continue;
                }
                for (List<TargetEnumerator.Part> targets : TargetEnumerator.enumerate(sa, MAX_TARGETINGS)) {
                    TargetEnumerator.apply(sa, targets);
                    if (TargetEnumerator.valid(sa) && ComputerUtilCost.canPayCost(sa, player, false)) {
                        String key = baseKey + " -> " + TargetEnumerator.key(targets, eq);
                        if (byKey.putIfAbsent(key, new PlayOption(sa, x, targets)) == null) {
                            labelByKey.put(key, base + " -> " + TargetEnumerator.describe(targets));
                        }
                    }
                }
                TargetEnumerator.clear(TargetEnumerator.targetingParts(sa));
            }
            if (sa.costHasManaX()) {
                sa.setXManaCostPaid(null);
            }
        }
        // Distinct options need distinct labels: tell same-named sources apart by card id.
        Map<String, PlayOption> options = new LinkedHashMap<>();
        for (Map.Entry<String, PlayOption> e : byKey.entrySet()) {
            String label = labelByKey.get(e.getKey());
            if (options.containsKey(label)) {
                label += " (#" + e.getValue().sa().getHostCard().getId() + ")";
            }
            options.put(label, e.getValue());
        }

        List<String> labels = new ArrayList<>(options.keySet());
        labels.add("Pass"); // last: Branch.step() relies on it
        // Positions are only serialised with an empty stack (stack contents aren't in the dump).
        int choice = decide("priority", labels, game.getStack().isEmpty());
        if (choice == labels.size() - 1) {
            return null;
        }
        PlayOption option = options.get(labels.get(choice));
        option.prepare();
        return List.of(option.sa());
    }

    /**
     * Values of X to offer: every affordable value from the cost's minimum up (Forge's AI would
     * pick one by heuristics, or leave it at 0). A single null for abilities without X.
     */
    private List<Integer> xValues(SpellAbility sa) {
        List<Integer> values = new ArrayList<>();
        if (!sa.costHasManaX()) {
            values.add(null);
            return values;
        }
        int min = sa.getPayCosts().getCostMana() == null ? 0 : sa.getPayCosts().getCostMana().getXMin();
        int max = Math.min(MAX_X, ComputerUtilMana.determineLeftoverMana(sa, player, false));
        for (int x = min; x <= max; x++) {
            values.add(x);
        }
        return values;
    }

    /**
     * True for priority windows that offer nothing the previous window in the turn didn't, so
     * both players pass without branching:
     * <ul>
     * <li>the draw step when nobody drew (the library was empty): it's the upkeep again;</li>
     * <li>the end of combat step right after the combat damage step: nothing happens in
     *     between (end-of-combat triggers would be on the stack, and creatures stay in combat
     *     until the step ends).</li>
     * </ul>
     * Anything done here could have been done, with the same result, in that earlier window.
     */
    private boolean repeatsEarlierWindow(PhaseType previous) {
        Game game = getGame();
        PhaseType phase = game.getPhaseHandler().getPhase();
        if (phase == PhaseType.DRAW) {
            return previous == PhaseType.UPKEEP
                    && game.getPlayers().stream().allMatch(p -> p.numDrawnThisDrawStep() == 0);
        }
        return phase == PhaseType.COMBAT_END && previous == PhaseType.COMBAT_DAMAGE;
    }

    /** Every non-mana spell/ability the player could legally start playing right now. */
    private List<SpellAbility> candidateAbilities() {
        Game game = getGame();
        CardCollection cards = ComputerUtilAbility.getAvailableCards(game, player);
        cards = ComputerUtilCard.dedupeCards(cards);
        List<SpellAbility> all = ComputerUtilAbility.getSpellAbilities(cards, player);
        List<SpellAbility> result = new ArrayList<>();
        for (SpellAbility sa : ComputerUtilAbility.getOriginalAndAltCostAbilities(all, player)) {
            if (sa.isManaAbility()) {
                continue;
            }
            sa.setActivatingPlayer(player);
            if (!sa.checkRestrictions(sa.getHostCard(), player)) {
                continue;
            }
            if (sa.isLandAbility()) {
                result.add(sa);
                continue;
            }
            if (!sa.isLegalAfterStack() || !sa.canPlay()) {
                continue;
            }
            if (TargetEnumerator.targetingParts(sa).isEmpty() && !sa.costHasManaX() && !ComputerUtilCost.canPayCost(sa, player, false)) {
                continue;
            }
            result.add(sa);
        }
        return result;
    }

    static String describe(SpellAbility sa) {
        Card host = sa.getHostCard();
        String name = host.getName();
        if (sa.isLandAbility()) {
            return "Play " + name;
        }
        if (sa.isSpell()) {
            // Distinguish alternative costs and multi-spell cards (split, adventure, MDFC)
            boolean ambiguous = sa.getAlternativeCost() != null || host.getAllSpellAbilities().stream().filter(SpellAbility::isSpell).count() > 1;
            return "Cast " + name + (ambiguous ? " (" + sa + ")" : "");
        }
        String where = host.getZone() == null ? "" : " [" + host.getZone().getZoneType() + (host.isTapped() ? ", tapped" : "") + "]";
        return "Activate " + name + where + ": " + sa;
    }

    // ---------------------------------------------------------------- combat

    @Override
    public void declareAttackers(Player attacker, Combat combat) {
        List<Card> creatures = new ArrayList<>();
        for (Card c : attacker.getCreaturesInPlay()) {
            if (CombatUtil.canAttack(c)) {
                creatures.add(c);
            }
        }
        List<GameEntity> defenders = new ArrayList<>(combat.getDefenders());
        if (creatures.isEmpty() || defenders.isEmpty()) {
            return;
        }
        // Keys before trying assignments on the combat: options that only swap interchangeable
        // creatures are merged.
        Equivalence eq = equivalence();
        List<String> creatureKeys = keys(creatures, eq);
        List<String> defenderKeys = keys(defenders, eq);
        // assignment[i] = 0 (doesn't attack) or 1 + index into defenders
        List<int[]> assignments = new ArrayList<>();
        int radix = defenders.size() + 1;
        int total = 1;
        for (int i = 0; i < creatures.size() && total <= MAX_OPTIONS * radix; i++) {
            total *= radix;
        }
        if (total > MAX_OPTIONS * radix) {
            super.declareAttackers(attacker, combat);
            return;
        }
        for (int code = 0; code < total; code++) {
            int[] a = new int[creatures.size()];
            int rest = code;
            boolean legal = true;
            for (int i = 0; i < a.length; i++) {
                a[i] = rest % radix;
                rest /= radix;
                if (a[i] > 0 && !CombatUtil.canAttack(creatures.get(i), defenders.get(a[i] - 1))) {
                    legal = false;
                }
            }
            if (legal && attackIsValid(combat, creatures, defenders, a)) {
                assignments.add(a);
            }
        }
        assignments = distinct(assignments, creatureKeys, defenderKeys);
        // Try the biggest attacks first; "no attack" last.
        assignments.sort(Comparator.comparingInt(SolverController::attackerCount).reversed());
        List<String> labels = new ArrayList<>();
        for (int[] a : assignments) {
            labels.add(describeAttack(creatures, defenders, a));
        }
        if (labels.isEmpty()) {
            return;
        }
        int[] chosen = assignments.get(decide("attack", labels, getGame().getStack().isEmpty()));
        applyAttack(combat, creatures, defenders, chosen);
    }

    private static List<String> keys(List<? extends GameObject> objects, Equivalence eq) {
        List<String> keys = new ArrayList<>();
        for (GameObject o : objects) {
            keys.add(eq.key(o));
        }
        return keys;
    }

    /**
     * Drops assignments that only swap interchangeable objects. {@code a[i]} sends creature i to
     * target {@code a[i] - 1} (0 = not assigned); creatures and targets with equal keys are
     * interchangeable.
     */
    private static List<int[]> distinct(List<int[]> assignments, List<String> creatureKeys, List<String> targetKeys) {
        Map<String, int[]> seen = new LinkedHashMap<>();
        for (int[] a : assignments) {
            // For each target: the sorted creatures sent to it. Then sort those groups, so two
            // identical targets with swapped groups also compare equal.
            List<String> groups = new ArrayList<>();
            for (int t = 0; t < targetKeys.size(); t++) {
                List<String> sent = new ArrayList<>();
                for (int i = 0; i < a.length; i++) {
                    if (a[i] == t + 1) {
                        sent.add(creatureKeys.get(i));
                    }
                }
                if (!sent.isEmpty()) {
                    sent.sort(null);
                    groups.add(targetKeys.get(t) + " <- " + String.join(" + ", sent));
                }
            }
            groups.sort(null);
            seen.putIfAbsent(String.join(" | ", groups), a);
        }
        return new ArrayList<>(seen.values());
    }

    private static int attackerCount(int[] a) {
        int n = 0;
        for (int x : a) {
            if (x > 0) n++;
        }
        return n;
    }

    private static boolean attackIsValid(Combat combat, List<Card> creatures, List<GameEntity> defenders, int[] a) {
        applyAttack(combat, creatures, defenders, a);
        boolean valid = CombatUtil.validateAttackers(combat);
        for (Card c : creatures) {
            combat.removeFromCombat(c);
        }
        return valid;
    }

    private static void applyAttack(Combat combat, List<Card> creatures, List<GameEntity> defenders, int[] a) {
        for (int i = 0; i < a.length; i++) {
            if (a[i] > 0) {
                combat.addAttacker(creatures.get(i), defenders.get(a[i] - 1));
            }
        }
    }

    private static String describeAttack(List<Card> creatures, List<GameEntity> defenders, int[] a) {
        List<String> parts = new ArrayList<>();
        for (int i = 0; i < a.length; i++) {
            if (a[i] > 0) {
                parts.add(creatures.get(i).getName() + " -> " + defenders.get(a[i] - 1));
            }
        }
        return parts.isEmpty() ? "No attack" : String.join(", ", parts);
    }

    @Override
    public void declareBlockers(Player defender, Combat combat) {
        List<Card> attackers = new ArrayList<>(combat.getAttackers());
        List<Card> blockers = new ArrayList<>();
        for (Card c : defender.getCreaturesInPlay()) {
            if (CombatUtil.canBlock(c, combat)) {
                blockers.add(c);
            }
        }
        if (attackers.isEmpty() || blockers.isEmpty()) {
            return;
        }
        int radix = attackers.size() + 1;
        int total = 1;
        for (int i = 0; i < blockers.size() && total <= MAX_OPTIONS * radix; i++) {
            total *= radix;
        }
        if (total > MAX_OPTIONS * radix) {
            super.declareBlockers(defender, combat);
            return;
        }
        // Blocking either of two identical unblocked attackers with either of two identical
        // blockers is one choice.
        Equivalence eq = equivalence();
        List<String> blockerKeys = keys(blockers, eq);
        List<String> attackerKeys = keys(attackers, eq);
        List<int[]> assignments = new ArrayList<>();
        for (int code = 0; code < total; code++) {
            int[] b = new int[blockers.size()];
            int rest = code;
            boolean legal = true;
            for (int i = 0; i < b.length; i++) {
                b[i] = rest % radix;
                rest /= radix;
                if (b[i] > 0 && !CombatUtil.canBlock(attackers.get(b[i] - 1), blockers.get(i), combat)) {
                    legal = false;
                }
            }
            if (legal && blockIsValid(combat, defender, attackers, blockers, b)) {
                assignments.add(b);
            }
        }
        assignments = distinct(assignments, blockerKeys, attackerKeys);
        // Most blockers first; "No blocks" last.
        assignments.sort(Comparator.comparingInt(SolverController::attackerCount).reversed());
        List<String> labels = new ArrayList<>();
        for (int[] b : assignments) {
            labels.add(describeBlock(attackers, blockers, b));
        }
        if (labels.isEmpty()) {
            return;
        }
        int[] chosen = assignments.get(decide("block", labels, getGame().getStack().isEmpty()));
        applyBlock(combat, attackers, blockers, chosen);
    }

    private static boolean blockIsValid(Combat combat, Player defender, List<Card> attackers, List<Card> blockers, int[] b) {
        applyBlock(combat, attackers, blockers, b);
        boolean valid = CombatUtil.validateBlocks(combat, defender) == null;
        for (int i = 0; i < b.length; i++) {
            if (b[i] > 0) {
                combat.removeFromCombat(blockers.get(i));
            }
        }
        return valid;
    }

    private static void applyBlock(Combat combat, List<Card> attackers, List<Card> blockers, int[] b) {
        for (int i = 0; i < b.length; i++) {
            if (b[i] > 0) {
                combat.addBlocker(attackers.get(b[i] - 1), blockers.get(i));
            }
        }
    }

    private static String describeBlock(List<Card> attackers, List<Card> blockers, int[] b) {
        List<String> parts = new ArrayList<>();
        for (int i = 0; i < b.length; i++) {
            if (b[i] > 0) {
                parts.add(blockers.get(i).getName() + " blocks " + attackers.get(b[i] - 1).getName());
            }
        }
        return parts.isEmpty() ? "No blocks" : String.join(", ", parts);
    }

    // ---------------------------------------------------------------- mana payment

    /**
     * Which permanents pay a mana cost (Mox or land, which colour to keep open, Ancient Tomb's
     * damage...). Branches on each minimal set of tap-for-mana permanents that can pay; Forge's
     * AI then pays from that set, with the other permanents briefly set aside (marked tapped
     * without any game event).
     */
    @Override
    public boolean payManaCost(ManaCost toPay, CostPartMana costPartMana, SpellAbility sa, String prompt,
                               ManaConversionMatrix matrix, boolean effect) {
        if (toPay.isZero()) {
            return super.payManaCost(toPay, costPartMana, sa, prompt, matrix, effect);
        }
        List<Card> sources = new ArrayList<>();
        for (Card c : player.getCardsIn(ZoneType.Battlefield)) {
            if (c.isUntapped() && !c.getManaAbilities().isEmpty()
                    && c.getManaAbilities().stream().allMatch(ma -> ma.getPayCosts().hasTapCost())) {
                sources.add(c);
            }
        }
        if (sources.size() < 2 || sources.size() > 8) {
            return super.payManaCost(toPay, costPartMana, sa, prompt, matrix, effect);
        }
        Cost cost = new Cost(toPay, effect);
        Equivalence eq = equivalence();
        List<String> keys = keys(sources, eq);
        Map<String, Integer> plans = new LinkedHashMap<>(); // distinct minimal subsets, as bit masks
        List<Integer> found = new ArrayList<>();
        for (int size = 0; size <= sources.size(); size++) {
            for (int mask = 0; mask < (1 << sources.size()); mask++) {
                if (Integer.bitCount(mask) != size) {
                    continue;
                }
                final int m = mask;
                if (found.stream().anyMatch(f -> (f & m) == f)) {
                    continue; // a smaller set already pays
                }
                List<String> planKeys = new ArrayList<>();
                for (int i = 0; i < sources.size(); i++) {
                    if ((mask & (1 << i)) != 0) {
                        planKeys.add(keys.get(i));
                    }
                }
                planKeys.sort(null);
                String planKey = String.join(" + ", planKeys);
                if (plans.containsKey(planKey)) {
                    found.add(mask);
                    continue;
                }
                boolean pays;
                List<Card> aside = setAside(sources, mask);
                try {
                    pays = ComputerUtilMana.canPayManaCost(cost, sa, player, 0, effect);
                } finally {
                    restore(aside);
                }
                if (pays) {
                    found.add(mask);
                    plans.put(planKey, mask);
                }
            }
        }
        if (plans.size() <= 1) {
            return super.payManaCost(toPay, costPartMana, sa, prompt, matrix, effect);
        }
        List<Integer> masks = new ArrayList<>(plans.values());
        List<String> labels = new ArrayList<>();
        for (int mask : masks) {
            List<String> names = new ArrayList<>();
            for (int i = 0; i < sources.size(); i++) {
                if ((mask & (1 << i)) != 0) {
                    names.add(sources.get(i).getName());
                }
            }
            labels.add("Pay " + toPay + " for " + sa.getHostCard().getName() + " with "
                    + (names.isEmpty() ? "floating mana" : String.join(", ", names)));
        }
        int mask = masks.get(decide("mana", labels, false));
        List<Card> aside = setAside(sources, mask);
        try {
            return super.payManaCost(toPay, costPartMana, sa, prompt, matrix, effect);
        } finally {
            restore(aside);
        }
    }

    /** Marks the sources outside {@code mask} tapped (no events), so payment can't use them. */
    private static List<Card> setAside(List<Card> sources, int mask) {
        List<Card> aside = new ArrayList<>();
        for (int i = 0; i < sources.size(); i++) {
            if ((mask & (1 << i)) == 0 && sources.get(i).isUntapped()) {
                sources.get(i).setTapped(true);
                aside.add(sources.get(i));
            }
        }
        return aside;
    }

    private static void restore(List<Card> aside) {
        for (Card c : aside) {
            c.setTapped(false);
        }
    }

    // ---------------------------------------------------------------- combat damage

    /**
     * How an attacker (or a blocker blocking several attackers) divides its combat damage. Every
     * split among the blockers; with trample, damage to the player only once every blocker has
     * lethal damage. The rare cases this doesn't model fall back to Forge's AI.
     */
    @Override
    public Map<Card, Integer> assignCombatDamage(Card attacker, CardCollectionView blockers, CardCollectionView remaining,
                                                 int damageDealt, GameEntity defender, boolean overrideOrder) {
        boolean trample = attacker.hasKeyword(Keyword.TRAMPLE) && defender != null;
        boolean special = attacker.hasKeyword("You may assign CARDNAME's combat damage divided as you choose among "
                + "defending player and/or any number of creatures they control.");
        if (!overrideOrder || special || blockers.isEmpty() || damageDealt <= 0 || (blockers.size() == 1 && !trample)) {
            return super.assignCombatDamage(attacker, blockers, remaining, damageDealt, defender, overrideOrder);
        }
        List<Card> targets = new ArrayList<>(blockers);
        int[] lethal = new int[targets.size()];
        for (int i = 0; i < targets.size(); i++) {
            lethal[i] = ComputerUtilCombat.getEnoughDamageToKill(targets.get(i), damageDealt, attacker, true);
        }
        Equivalence eq = equivalence();
        List<String> keys = new ArrayList<>();
        for (Card c : targets) {
            keys.add(eq.keyAmongCombatants(c));
        }
        // split[i] for each blocker, then the player's share last
        List<int[]> splits = new ArrayList<>();
        splitDamage(damageDealt, new int[targets.size() + 1], 0, splits);
        Map<String, int[]> distinct = new LinkedHashMap<>();
        for (int[] split : splits) {
            int toPlayer = split[targets.size()];
            if (toPlayer > 0) {
                boolean allLethal = trample;
                for (int i = 0; i < targets.size() && allLethal; i++) {
                    allLethal = split[i] >= lethal[i];
                }
                if (!allLethal) {
                    continue;
                }
            }
            List<String> parts = new ArrayList<>();
            for (int i = 0; i < targets.size(); i++) {
                parts.add(keys.get(i) + " x" + split[i]);
            }
            parts.sort(null);
            distinct.putIfAbsent(String.join(" | ", parts) + " | player x" + toPlayer, split);
            if (distinct.size() > MAX_OPTIONS) {
                return super.assignCombatDamage(attacker, blockers, remaining, damageDealt, defender, overrideOrder);
            }
        }
        List<int[]> options = new ArrayList<>(distinct.values());
        List<String> labels = new ArrayList<>();
        for (int[] split : options) {
            List<String> parts = new ArrayList<>();
            for (int i = 0; i < targets.size(); i++) {
                if (split[i] > 0) {
                    parts.add(split[i] + " to " + targets.get(i).getName());
                }
            }
            if (split[targets.size()] > 0) {
                parts.add(split[targets.size()] + " to " + defender);
            }
            labels.add(attacker.getName() + " assigns " + (parts.isEmpty() ? "no damage" : String.join(", ", parts)));
        }
        int[] split = options.get(decide("damage assignment", labels, false));
        Map<Card, Integer> result = new LinkedHashMap<>();
        for (int i = 0; i < targets.size(); i++) {
            if (split[i] > 0) {
                result.put(targets.get(i), split[i]);
            }
        }
        if (split[targets.size()] > 0) {
            result.put(null, split[targets.size()]);
        }
        return result;
    }

    /** Every way to write {@code left} as an ordered sum of {@code cur.length} parts (each >= 0). */
    private static void splitDamage(int left, int[] cur, int i, List<int[]> out) {
        if (i == cur.length - 1) {
            cur[i] = left;
            out.add(cur.clone());
            return;
        }
        for (int x = left; x >= 0; x--) {
            cur[i] = x;
            splitDamage(left - x, cur, i + 1, out);
        }
    }

    // ---------------------------------------------------------------- generic choices

    private boolean yesNo(String kind, String question) {
        String q = question == null ? "" : question.replaceAll("\\s+", " ").strip();
        return decide(kind, List.of("Yes: " + q, "No: " + q), false) == 0;
    }

    @Override
    public boolean confirmAction(SpellAbility sa, PlayerActionConfirmMode mode, String message, List<String> options, Card cardToShow, Map<String, Object> params) {
        return yesNo("confirm", (sa == null ? "" : sa.getHostCard().getName() + ": ") + message);
    }

    @Override
    public boolean confirmTrigger(WrappedAbility wrapper) {
        if (!wrapper.isOptionalTrigger()) {
            return true;
        }
        return yesNo("optional trigger", wrapper.getHostCard().getName() + ": " + wrapper);
    }

    @Override
    public boolean chooseBinary(SpellAbility sa, String question, BinaryChoiceType kindOfChoice, Boolean defaultVal) {
        if (kindOfChoice == BinaryChoiceType.HeadsOrTails) {
            return true; // calling heads or tails can't change a fair flip
        }
        return yesNo("binary " + kindOfChoice, question);
    }

    @Override
    public int chooseNumber(SpellAbility sa, String title, int min, int max) {
        if (max - min + 1 > MAX_CHOICE || max < min) {
            return super.chooseNumber(sa, title, min, max);
        }
        List<String> labels = new ArrayList<>();
        for (int n = min; n <= max; n++) {
            labels.add(title + ": " + n);
        }
        return min + pick("number", labels);
    }

    /** Largest single choice enumerated (in steps of at most {@link #MAX_OPTIONS}); bigger ones use the AI. */
    static final int MAX_CHOICE = 4096;

    /**
     * {@link #decide} for any number of options: more than {@link #MAX_OPTIONS} are split into
     * groups, choosing a group first and then an option in it. Same choices, smaller branch points.
     */
    private int pick(String kind, List<String> labels) {
        if (labels.size() <= MAX_OPTIONS) {
            return decide(kind, labels, false);
        }
        int groupSize = MAX_OPTIONS;
        while ((labels.size() + groupSize - 1) / groupSize > MAX_OPTIONS) {
            groupSize *= MAX_OPTIONS;
        }
        List<String> groups = new ArrayList<>();
        for (int from = 0; from < labels.size(); from += groupSize) {
            int to = Math.min(labels.size(), from + groupSize) - 1;
            groups.add(labels.get(from) + " ... " + labels.get(to));
        }
        int from = decide(kind + " (range)", groups, false) * groupSize;
        return from + pick(kind, labels.subList(from, Math.min(labels.size(), from + groupSize)));
    }

    @Override
    public int chooseNumber(SpellAbility sa, String title, int min, int max, Map<String, Object> params) {
        return chooseNumber(sa, title, min, max);
    }

    @Override
    public int chooseNumber(SpellAbility sa, String title, List<Integer> values, Player relatedPlayer) {
        if (values.size() > MAX_CHOICE || values.isEmpty()) {
            return super.chooseNumber(sa, title, values, relatedPlayer);
        }
        List<String> labels = new ArrayList<>();
        for (Integer v : values) {
            labels.add(title + ": " + v);
        }
        return values.get(pick("number", labels));
    }

    @Override
    public <T extends GameEntity> T chooseSingleEntityForEffect(FCollectionView<T> optionList, DelayedReveal delayedReveal, SpellAbility sa, String title, boolean isOptional, Player targetedPlayer, Map<String, Object> params) {
        if (optionList.isEmpty() || optionList.size() > MAX_CHOICE) {
            return super.chooseSingleEntityForEffect(optionList, delayedReveal, sa, title, isOptional, targetedPlayer, params);
        }
        // One option per group of interchangeable items.
        Equivalence eq = equivalence();
        Map<String, T> distinct = new LinkedHashMap<>();
        for (T item : optionList) {
            distinct.putIfAbsent(eq.key(item), item);
        }
        List<T> items = new ArrayList<>(distinct.values());
        List<String> labels = new ArrayList<>();
        for (T item : items) {
            labels.add(title + ": " + item);
        }
        if (isOptional) {
            labels.add(title + ": (none)");
        }
        int choice = pick("choose", labels);
        return choice < items.size() ? items.get(choice) : null;
    }

    @Override
    public CardCollectionView chooseCardsForEffect(CardCollectionView sourceList, SpellAbility sa, String title, int min, int max, boolean isOptional, Map<String, Object> params) {
        List<Card> cards = new ArrayList<>(sourceList);
        int lo = isOptional ? 0 : Math.max(0, min);
        int hi = Math.min(max, cards.size());
        if (lo > hi) {
            return super.chooseCardsForEffect(sourceList, sa, title, min, max, isOptional, params);
        }
        // Subsets that only swap interchangeable cards are one option.
        Equivalence eq = equivalence();
        List<String> keys = keys(cards, eq);
        if (cards.size() > 12) {
            return chooseCardsOneByOne(cards, keys, lo, hi, title);
        }
        Map<String, CardCollection> distinct = new LinkedHashMap<>();
        for (int mask = 0; mask < (1 << cards.size()); mask++) {
            int size = Integer.bitCount(mask);
            if (size < lo || size > hi) {
                continue;
            }
            CardCollection subset = new CardCollection();
            List<String> subsetKeys = new ArrayList<>();
            for (int i = 0; i < cards.size(); i++) {
                if ((mask & (1 << i)) != 0) {
                    subset.add(cards.get(i));
                    subsetKeys.add(keys.get(i));
                }
            }
            subsetKeys.sort(null);
            distinct.putIfAbsent(String.join(" + ", subsetKeys), subset);
            if (distinct.size() > MAX_OPTIONS) {
                return chooseCardsOneByOne(cards, keys, lo, hi, title);
            }
        }
        List<CardCollection> subsets = new ArrayList<>(distinct.values());
        List<String> labels = new ArrayList<>();
        for (CardCollection subset : subsets) {
            labels.add(title + ": " + (subset.isEmpty() ? "(none)" : subset.toString()));
        }
        return subsets.get(decide("choose cards", labels, false));
    }

    /**
     * A subset of many cards, picked one card at a time (in list order, so each subset is reached
     * once up to identical cards), then "done".
     */
    private CardCollection chooseCardsOneByOne(List<Card> cards, List<String> keys, int lo, int hi, String title) {
        CardCollection chosen = new CardCollection();
        int last = -1;
        while (chosen.size() < hi) {
            // the first card of each kind after the last pick, if enough cards remain to reach lo
            Map<String, Integer> next = new LinkedHashMap<>();
            for (int i = last + 1; i < cards.size(); i++) {
                if (cards.size() - i >= lo - chosen.size()) {
                    next.putIfAbsent(keys.get(i), i);
                }
            }
            List<Integer> indices = new ArrayList<>(next.values());
            List<String> labels = new ArrayList<>();
            for (int i : indices) {
                labels.add(title + ": add " + cards.get(i));
            }
            if (chosen.size() >= lo) {
                labels.add(title + ": done");
            }
            if (labels.isEmpty()) {
                break;
            }
            int choice = pick("choose cards", labels);
            if (choice >= indices.size()) {
                break;
            }
            last = indices.get(choice);
            chosen.add(cards.get(last));
        }
        return chosen;
    }

    @Override
    public List<AbilitySub> chooseModeForAbility(SpellAbility sa, List<AbilitySub> possible, int min, int num, boolean allowRepeat) {
        if (allowRepeat || possible.size() > 10 || num < 1) {
            return super.chooseModeForAbility(sa, possible, min, num, allowRepeat);
        }
        List<List<AbilitySub>> combos = new ArrayList<>();
        for (int mask = 1; mask < (1 << possible.size()); mask++) {
            int size = Integer.bitCount(mask);
            if (size < Math.max(1, min) || size > num) {
                continue;
            }
            List<AbilitySub> combo = new ArrayList<>();
            for (int i = 0; i < possible.size(); i++) {
                if ((mask & (1 << i)) != 0) {
                    combo.add(possible.get(i));
                }
            }
            if (combo.stream().allMatch(SolverController::hasTargets)) {
                combos.add(combo);
            }
        }
        if (combos.isEmpty() || combos.size() > MAX_OPTIONS) {
            return super.chooseModeForAbility(sa, possible, min, num, allowRepeat);
        }
        List<String> labels = new ArrayList<>();
        for (List<AbilitySub> combo : combos) {
            List<String> parts = new ArrayList<>();
            for (AbilitySub sub : combo) {
                parts.add(sub.getDescription());
            }
            labels.add(sa.getHostCard().getName() + " modes: " + String.join(" + ", parts));
        }
        List<AbilitySub> chosen = combos.get(decide("mode", labels, false));
        // Forge's AI picks the chosen modes' targets while choosing them; branch on those too.
        for (AbilitySub sub : chosen) {
            chooseTargets(sub, "mode target");
        }
        return chosen;
    }

    private static boolean hasTargets(SpellAbility sa) {
        return TargetEnumerator.targetingParts(sa).isEmpty() || !TargetEnumerator.enumerate(sa, 1).isEmpty();
    }

    // ---------------------------------------------------------------- targets outside priority

    /**
     * Branches on every legal target assignment (and division) for {@code sa}'s chain, as for
     * spells cast at priority. Returns false if there is no legal assignment.
     */
    private boolean chooseTargets(SpellAbility sa, String kind) {
        List<SpellAbility> parts = TargetEnumerator.targetingParts(sa);
        if (parts.isEmpty()) {
            return true;
        }
        Equivalence eq = equivalence();
        Map<String, List<TargetEnumerator.Part>> byKey = new LinkedHashMap<>();
        Map<String, List<TargetEnumerator.Part>> options = new LinkedHashMap<>();
        for (List<TargetEnumerator.Part> targets : TargetEnumerator.enumerate(sa, MAX_TARGETINGS)) {
            TargetEnumerator.apply(sa, targets);
            if (TargetEnumerator.valid(sa) && byKey.putIfAbsent(TargetEnumerator.key(targets, eq), targets) == null) {
                options.putIfAbsent(sa.getHostCard().getName() + " -> " + TargetEnumerator.describe(targets), targets);
            }
        }
        TargetEnumerator.clear(parts);
        if (options.isEmpty()) {
            return false;
        }
        List<String> labels = new ArrayList<>(options.keySet());
        TargetEnumerator.apply(sa, options.get(labels.get(decide(kind, labels, false))));
        return true;
    }

    /** Targets for a triggered ability, a copy, or an ability whose targets another player picks. */
    @Override
    public boolean chooseTargetsFor(SpellAbility sa) {
        return chooseTargets(sa, "targets");
    }

    @Override
    public void playSpellAbilityNoStack(SpellAbility effectSA, boolean canSetupTargets) {
        if (canSetupTargets && !chooseTargets(effectSA, "targets")) {
            return;
        }
        ComputerUtil.playNoStack(player, effectSA, getGame(), true);
    }

    @Override
    public boolean playTrigger(Card host, WrappedAbility wrapperAbility, boolean isMandatory) {
        if (prepareTrigger(wrapperAbility)) {
            return ComputerUtil.playNoStack(wrapperAbility.getActivatingPlayer(), wrapperAbility, getGame(), true);
        }
        return false;
    }

    /** Puts simultaneous triggers on the stack in a chosen order, with chosen targets. */
    @Override
    public void orderAndPlaySimultaneousSa(List<SpellAbility> activePlayerSAs) {
        for (SpellAbility sa : orderSimultaneousSa(activePlayerSAs)) {
            if (sa.isTrigger() && !sa.isCopied()) {
                if (prepareTrigger(sa)) {
                    ComputerUtil.playStack(sa, player, getGame());
                }
            } else {
                super.orderAndPlaySimultaneousSa(List.of(sa)); // copies: Forge's handling
            }
        }
    }

    /**
     * Modes and targets for a triggered ability (Forge's AI decides these heuristically). False if
     * it can't be put on the stack, e.g. no legal targets.
     */
    private boolean prepareTrigger(SpellAbility sa) {
        if (sa.getApi() == ApiType.Charm) {
            if (!CharmEffect.makeChoices(sa)) {
                return false;
            }
            if (!sa.hasParam("Random")) {
                return true; // the chosen modes' targets were picked with the modes
            }
            sa = sa.getSubAbility();
        }
        if (sa.hasParam("TargetingPlayer")) {
            Player targeting = AbilityUtils.getDefinedPlayers(sa.getHostCard(), sa.getParam("TargetingPlayer"), sa).get(0);
            sa.setTargetingPlayer(targeting);
            return targeting.getController().chooseTargetsFor(sa);
        }
        return chooseTargets(sa, "trigger targets");
    }

    /** The order simultaneous triggers go on the stack (last one resolves first). */
    @Override
    public List<SpellAbility> orderSimultaneousSa(List<SpellAbility> sas) {
        if (sas.size() <= 1 || sas.size() > 4) {
            return sas.size() <= 1 ? sas : super.orderSimultaneousSa(sas);
        }
        Equivalence eq = equivalence();
        Map<String, List<SpellAbility>> orders = new LinkedHashMap<>();
        for (List<SpellAbility> order : permutations(sas)) {
            List<String> keys = new ArrayList<>();
            for (SpellAbility sa : order) {
                keys.add(eq.key(sa.getHostCard()) + ": " + sa.getDescription());
            }
            orders.putIfAbsent(String.join(" | ", keys), order);
        }
        List<List<SpellAbility>> distinct = new ArrayList<>(orders.values());
        List<String> labels = new ArrayList<>();
        for (List<SpellAbility> order : distinct) {
            List<String> names = new ArrayList<>();
            for (SpellAbility sa : order) {
                names.add(sa.getHostCard().getName() + ": " + sa.getDescription());
            }
            String label = "Stack " + String.join(", then ", names);
            labels.add(labels.contains(label) ? label + " (" + labels.size() + ")" : label);
        }
        return distinct.get(decide("trigger order", labels, false));
    }

    private static <T> List<List<T>> permutations(List<T> items) {
        List<List<T>> out = new ArrayList<>();
        if (items.size() <= 1) {
            out.add(new ArrayList<>(items));
            return out;
        }
        for (int i = 0; i < items.size(); i++) {
            List<T> rest = new ArrayList<>(items);
            T first = rest.remove(i);
            for (List<T> tail : permutations(rest)) {
                tail.add(0, first);
                out.add(tail);
            }
        }
        return out;
    }

    // ---------------------------------------------------------------- position key

    /**
     * Canonical description of the current position, without the turn number (so repeated
     * positions on later turns can be detected). Built from Forge's own game-state dump plus the
     * per-turn counters that dump omits.
     */
    private String position(String kind, List<String> labels) {
        Game game = getGame();
        GameState state = new GameState();
        state.initFromGame(game);
        String dump = state.toString().replaceFirst("(?m)^turn=\\d+\n", "");
        StringBuilder sb = new StringBuilder(dump);
        PhaseType phase = game.getPhaseHandler().getPhase();
        sb.append("|phase=").append(phase)
          .append("|active=").append(game.getPlayers().indexOf(game.getPhaseHandler().getPlayerTurn()))
          .append("|decider=").append(me());
        for (Player p : game.getPlayers()) {
            sb.append("|p").append(game.getPlayers().indexOf(p))
              .append(":spells=").append(p.getSpellsCastThisTurn())
              .append(",lands=").append(p.getLandsPlayedThisTurn())
              .append(",gy=").append(p.getCardsIn(ZoneType.Graveyard).size());
        }
        // The dump leaves out temporary changes (P/T, types, keywords); add each permanent's state.
        List<String> permanents = new ArrayList<>();
        for (Card c : game.getCardsIn(ZoneType.Battlefield)) {
            permanents.add(Equivalence.state(game, c));
        }
        permanents.sort(null);
        sb.append("|permanents:").append(String.join(";", permanents));
        Combat combat = game.getPhaseHandler().getCombat();
        if (combat != null) {
            sb.append("|combat:");
            for (Card attacker : combat.getAttackers()) {
                sb.append(attacker.getId()).append("->").append(combat.getDefenderByAttacker(attacker));
                if (combat.isBlocked(attacker)) {
                    sb.append(" blocked by");
                    for (Card blocker : combat.getBlockers(attacker)) {
                        sb.append(' ').append(blocker.getId());
                    }
                }
                sb.append(';');
            }
        }
        sb.append('|').append(kind).append('|').append(String.join("\u0000", labels));
        return sb.toString();
    }
}
