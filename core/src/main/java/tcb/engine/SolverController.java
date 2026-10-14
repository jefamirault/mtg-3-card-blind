package tcb.engine;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import forge.ai.AiCache;
import forge.ai.ComputerUtilAbility;
import forge.ai.ComputerUtilCard;
import forge.ai.ComputerUtilCost;
import forge.ai.PlayerControllerAi;
import forge.game.Game;
import forge.game.GameEntity;
import forge.game.GameObject;
import forge.game.GameState;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.card.CardCollectionView;
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
 * (what to cast and at which targets, attacks, blocks, yes/no prompts, numbers, modes, single picks)
 * becomes a branch point of the game tree.
 *
 * Choices not yet enumerated here fall back to Forge's AI heuristics (inherited from
 * {@link PlayerControllerAi}); see README "Known limitations".
 */
public class SolverController extends PlayerControllerAi {
    /** Upper bound on options enumerated for a single decision; larger choices fall back to the AI. */
    static final int MAX_OPTIONS = 64;

    private final DecisionTape tape;
    private final int startingPlayer;

    public SolverController(Game game, Player player, forge.LobbyPlayer lobbyPlayer, DecisionTape tape, int startingPlayer) {
        super(game, player, lobbyPlayer);
        this.tape = tape;
        this.startingPlayer = startingPlayer;
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

    // ---------------------------------------------------------------- priority

    private record PlayOption(SpellAbility sa, List<List<GameObject>> targets) {
    }

    @Override
    public List<SpellAbility> chooseSpellAbilityToPlay() {
        AiCache.clear(); // as Forge's AI does at each priority decision; the AI fallbacks rely on it
        Game game = getGame();
        int turn = game.getPhaseHandler().getTurn();
        tape.checkTurn(turn);
        if (game.getPhaseHandler().is(PhaseType.MAIN1, player) && game.getStack().isEmpty() && tape.checkpointDue(turn)) {
            tape.checkpoint(position("checkpoint", List.of()));
        }
        if (tape.replaysPass(me())) {
            return null;
        }

        Map<String, PlayOption> options = new LinkedHashMap<>();
        for (SpellAbility sa : candidateAbilities()) {
            String base = describe(sa);
            if (TargetEnumerator.targetingParts(sa).isEmpty()) {
                options.putIfAbsent(base, new PlayOption(sa, null));
                continue;
            }
            for (List<List<GameObject>> targets : TargetEnumerator.enumerate(sa, MAX_OPTIONS)) {
                TargetEnumerator.apply(sa, targets);
                if (sa.isTargetNumberValid() && ComputerUtilCost.canPayCost(sa, player, false)) {
                    options.putIfAbsent(base + " -> " + TargetEnumerator.describe(targets), new PlayOption(sa, targets));
                }
            }
            TargetEnumerator.clear(TargetEnumerator.targetingParts(sa));
        }

        List<String> labels = new ArrayList<>(options.keySet());
        labels.add("Pass"); // last: Branch.step() relies on it
        // Positions are only serialised with an empty stack (stack contents aren't in the dump).
        int choice = decide("priority", labels, game.getStack().isEmpty());
        if (choice == labels.size() - 1) {
            return null;
        }
        PlayOption option = options.get(labels.get(choice));
        if (option.targets() != null) {
            TargetEnumerator.apply(option.sa(), option.targets());
        }
        return List.of(option.sa());
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
            if (TargetEnumerator.targetingParts(sa).isEmpty() && !ComputerUtilCost.canPayCost(sa, player, false)) {
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
        if (max - min + 1 > MAX_OPTIONS || max < min) {
            return super.chooseNumber(sa, title, min, max);
        }
        List<String> labels = new ArrayList<>();
        for (int n = min; n <= max; n++) {
            labels.add(title + ": " + n);
        }
        return min + decide("number", labels, false);
    }

    @Override
    public int chooseNumber(SpellAbility sa, String title, int min, int max, Map<String, Object> params) {
        return chooseNumber(sa, title, min, max);
    }

    @Override
    public int chooseNumber(SpellAbility sa, String title, List<Integer> values, Player relatedPlayer) {
        if (values.size() > MAX_OPTIONS || values.isEmpty()) {
            return super.chooseNumber(sa, title, values, relatedPlayer);
        }
        List<String> labels = new ArrayList<>();
        for (Integer v : values) {
            labels.add(title + ": " + v);
        }
        return values.get(decide("number", labels, false));
    }

    @Override
    public <T extends GameEntity> T chooseSingleEntityForEffect(FCollectionView<T> optionList, DelayedReveal delayedReveal, SpellAbility sa, String title, boolean isOptional, Player targetedPlayer, Map<String, Object> params) {
        if (optionList.isEmpty() || optionList.size() > MAX_OPTIONS) {
            return super.chooseSingleEntityForEffect(optionList, delayedReveal, sa, title, isOptional, targetedPlayer, params);
        }
        List<T> items = new ArrayList<>(optionList);
        List<String> labels = new ArrayList<>();
        for (T item : items) {
            labels.add(title + ": " + item);
        }
        if (isOptional) {
            labels.add(title + ": (none)");
        }
        int choice = decide("choose", labels, false);
        return choice < items.size() ? items.get(choice) : null;
    }

    @Override
    public CardCollectionView chooseCardsForEffect(CardCollectionView sourceList, SpellAbility sa, String title, int min, int max, boolean isOptional, Map<String, Object> params) {
        List<Card> cards = new ArrayList<>(sourceList);
        int lo = isOptional ? 0 : Math.max(0, min);
        int hi = Math.min(max, cards.size());
        if (cards.size() > 12 || lo > hi) {
            return super.chooseCardsForEffect(sourceList, sa, title, min, max, isOptional, params);
        }
        List<CardCollection> subsets = new ArrayList<>();
        for (int mask = 0; mask < (1 << cards.size()); mask++) {
            int size = Integer.bitCount(mask);
            if (size < lo || size > hi) {
                continue;
            }
            CardCollection subset = new CardCollection();
            for (int i = 0; i < cards.size(); i++) {
                if ((mask & (1 << i)) != 0) {
                    subset.add(cards.get(i));
                }
            }
            subsets.add(subset);
            if (subsets.size() > MAX_OPTIONS) {
                return super.chooseCardsForEffect(sourceList, sa, title, min, max, isOptional, params);
            }
        }
        List<String> labels = new ArrayList<>();
        for (CardCollection subset : subsets) {
            labels.add(title + ": " + (subset.isEmpty() ? "(none)" : subset.toString()));
        }
        return subsets.get(decide("choose cards", labels, false));
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
            combos.add(combo);
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
        return combos.get(decide("mode", labels, false));
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
