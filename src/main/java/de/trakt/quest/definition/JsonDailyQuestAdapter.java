package de.trakt.quest.definition;

import de.trakt.api.ApiConfig;
import de.trakt.quest.QuestCompletionMode;
import de.trakt.quest.daily.DailyQuestCompletion;
import de.trakt.quest.daily.DailyQuestDefinition;
import de.trakt.quest.daily.DailyQuestService;
import de.trakt.util.Texts;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.sheep.Sheep;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.BeehiveBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.biome.Biome;

/**
 * Runs a {@link QuestDefinition} on the built-in daily quest engine. F1a wired the
 * data model to the engine hooks; F2a made the dispatch generic: every hook filters
 * leaves through the {@link QuestGoalTypes} registry descriptors, and crediting goes
 * through the objective tree's gates — so composed objectives (all_of/any_of/sequence)
 * and newly registered kinds work without engine changes.
 *
 * <p>Per-leaf progress counters, tuned targets and the questmaster turn-in item
 * consumption are unchanged from the retired {@code content/daily/*} classes.
 */
public final class JsonDailyQuestAdapter implements DailyQuestDefinition {

    private final DailyQuestService.DailyQuestType slot;
    private final QuestDefinition definition;

    public JsonDailyQuestAdapter(DailyQuestService.DailyQuestType slot, QuestDefinition definition) {
        this.slot = slot;
        this.definition = definition;
    }

    public QuestDefinition definition() {
        return definition;
    }

    @Override
    public DailyQuestService.DailyQuestType type() {
        return slot;
    }

    @Override
    public QuestCompletionMode completionMode() {
        return definition.completionMode() == QuestDefinition.CompletionMode.QUESTMASTER_TURN_IN
                ? QuestCompletionMode.QUESTMASTER_TURN_IN
                : QuestCompletionMode.AUTOMATIC;
    }

    // ------------------------------------------------------------------ texts

    @Override
    public Component title() {
        if (definition.usesLangKeys()) {
            return Component.translatable(definition.titleKey());
        }
        return Component.literal(definition.title(locale()));
    }

    @Override
    public Component offerParagraph1() {
        return offerLine(0);
    }

    @Override
    public Component offerParagraph2() {
        return offerLine(1);
    }

    private Component offerLine(int index) {
        if (definition.usesLangKeys()) {
            if (index < definition.offerKeys().size()) {
                return Component.translatable(definition.offerKeys().get(index)).withStyle(ChatFormatting.GRAY);
            }
            return Component.empty();
        }
        List<String> paragraphs = definition.offerParagraphs(locale());
        if (index < paragraphs.size()) {
            return Component.literal(paragraphs.get(index)).withStyle(ChatFormatting.GRAY);
        }
        return Component.empty();
    }

    private static String locale() {
        try {
            return ApiConfig.get().defaultLocale();
        } catch (RuntimeException exception) {
            return "en_us";
        }
    }

    // ------------------------------------------------------------------ targets & progress state

    private QuestGoal goal(int index) {
        return definition.goals().get(index);
    }

    private String progressKey(int index) {
        return goal(index).progressKey(definition.id());
    }

    private int target(int index, ServerLevel world, UUID playerId) {
        QuestGoal goal = goal(index);
        if (goal.salt() == null) {
            // fixed-target quests (the combat pack) never went through the tuner
            return goal.amount();
        }
        return DailyQuestService.tunedTargetFor(goal.amount(), goal.salt());
    }

    private int progress(int index, ServerLevel world, UUID playerId) {
        return DailyQuestService.getQuestInt(world, playerId, progressKey(index));
    }

    private int storedTarget(int index, ServerLevel world, UUID playerId) {
        return target(index, world, playerId);
    }

    /**
     * Credit gate of leaf {@code index}: every leaf listed in its gate set (staged
     * siblings and sequence ancestors, see {@link QuestDefinition#goalGates()}) must
     * have reached its target. Empty sets credit immediately — the v1 behaviour of
     * every non-staged goal.
     */
    private boolean gateSatisfied(int index, ServerLevel world, UUID playerId) {
        Set<Integer> gates = definition.goalGates().get(index);
        if (gates == null || gates.isEmpty()) {
            return true;
        }
        for (int gateIndex : gates) {
            if (progress(gateIndex, world, playerId) < storedTarget(gateIndex, world, playerId)) {
                return false;
            }
        }
        return true;
    }

    /** Completion of the whole objective tree (all_of root == the v1 all-goals check). */
    private boolean rootComplete(ServerLevel world, UUID playerId) {
        return QuestObjective.isComplete(definition.objective(),
                index -> progress(index, world, playerId) >= storedTarget(index, world, playerId));
    }

    // ------------------------------------------------------------------ view

    @Override
    public Component progressLine(ServerLevel world, UUID playerId) {
        if (rootComplete(world, playerId)) {
            ServerPlayer player = world == null ? null : world.getServer().getPlayerList().getPlayer(playerId);
            Component blocked = player == null ? null : claimBlockedMessage(world, player);
            if (blocked != null) {
                return blocked;
            }
        }
        QuestDefinition.ProgressView view = definition.progressView();
        if (view == null) {
            return defaultProgressLine(world, playerId);
        }
        if (view.key() != null) {
            List<Object> args = new ArrayList<>();
            for (int i = 0; i < definition.goals().size(); i++) {
                args.add(progress(i, world, playerId));
                args.add(storedTarget(i, world, playerId));
            }
            return Component.translatable(view.key(), args.toArray()).withStyle(ChatFormatting.GRAY);
        }
        List<String> stages = view.stages();
        for (int i = 0; i < definition.goals().size() && i < stages.size(); i++) {
            if (progress(i, world, playerId) < storedTarget(i, world, playerId)) {
                return Component.translatable(stages.get(i),
                                progress(i, world, playerId), storedTarget(i, world, playerId))
                        .withStyle(ChatFormatting.GRAY);
            }
        }
        if (!stages.isEmpty()) {
            String last = stages.get(stages.size() - 1);
            return Component.translatable(last).withStyle(ChatFormatting.GRAY);
        }
        return defaultProgressLine(world, playerId);
    }

    private Component defaultProgressLine(ServerLevel world, UUID playerId) {
        StringBuilder line = new StringBuilder();
        for (int i = 0; i < definition.goals().size(); i++) {
            if (line.length() > 0) {
                line.append(" · ");
            }
            line.append(goal(i).kind().id()).append(' ')
                    .append(progress(i, world, playerId)).append('/')
                    .append(storedTarget(i, world, playerId));
        }
        return Component.literal(definition.title(locale()) + ": " + line).withStyle(ChatFormatting.GRAY);
    }

    // ------------------------------------------------------------------ completion

    @Override
    public boolean isComplete(ServerLevel world, ServerPlayer player) {
        UUID playerId = player.getUUID();
        if (!rootComplete(world, playerId)) {
            return false;
        }
        return turnInSatisfied(world, player);
    }

    @Override
    public boolean consumeCompletionRequirements(ServerLevel world, ServerPlayer player) {
        if (definition.turnInItems().isEmpty()) {
            return true;
        }
        if (!hasTurnInItems(world, player)) {
            return false;
        }
        for (QuestDefinition.TurnInItem item : definition.turnInItems()) {
            List<Item> resolved = turnInItemsOf(item.item());
            if (!resolved.isEmpty() && !consumeTurnIn(world, player, item, resolved)) {
                return false;
            }
        }
        return true;
    }

    @Override
    public Component claimBlockedMessage(ServerLevel world, ServerPlayer player) {
        if (definition.turnInItems().isEmpty() || world == null || player == null) {
            return null;
        }
        UUID playerId = player.getUUID();
        if (!rootComplete(world, playerId) || hasTurnInItems(world, player)) {
            return null;
        }
        return blockedMessageIfAny(world, player);
    }

    /** Builds the "bring the items" line for turn-in quests (up to 4 stacks). */
    private Component blockedMessageIfAny(ServerLevel world, ServerPlayer player) {
        if (definition.turnInItems().isEmpty() || player == null) {
            return null;
        }
        List<Component> labels = new ArrayList<>();
        List<Integer> currents = new ArrayList<>();
        List<Integer> targets = new ArrayList<>();
        for (QuestDefinition.TurnInItem item : definition.turnInItems()) {
            List<Item> resolved = turnInItemsOf(item.item());
            if (resolved.isEmpty()) {
                continue;
            }
            labels.add(turnInLabel(item.item(), resolved));
            currents.add(countTurnIn(world, player, item, resolved));
            targets.add(turnInCount(item));
        }
        return switch (labels.size()) {
            case 1 -> Texts.turnInMissing(labels.get(0), currents.get(0), targets.get(0));
            case 2 -> Texts.turnInMissing(labels.get(0), currents.get(0), targets.get(0),
                    labels.get(1), currents.get(1), targets.get(1));
            case 3 -> Texts.turnInMissing(labels.get(0), currents.get(0), targets.get(0),
                    labels.get(1), currents.get(1), targets.get(1),
                    labels.get(2), currents.get(2), targets.get(2));
            case 4 -> Texts.turnInMissing(labels.get(0), currents.get(0), targets.get(0),
                    labels.get(1), currents.get(1), targets.get(1),
                    labels.get(2), currents.get(2), targets.get(2),
                    labels.get(3), currents.get(3), targets.get(3));
            default -> null;
        };
    }

    /** Tuned turn-in amount: same target profile treatment the retired classes applied. */
    private int turnInCount(QuestDefinition.TurnInItem item) {
        if (item.salt() == null) {
            return item.count();
        }
        return DailyQuestService.tunedTargetFor(item.count(), item.salt());
    }

    /**
     * F2b: a turn-in reference resolves to a plain item or — when it starts with
     * {@code #} — to every item of an item tag (either-or bundle, e.g. any cooked
     * fish for the river meal).
     */
    private static List<Item> turnInItemsOf(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        if (raw.startsWith("#")) {
            TagKey<Item> tag = TagKey.create(Registries.ITEM,
                    Identifier.fromNamespaceAndPath(tagNamespace(raw), tagPath(raw)));
            List<Item> result = new ArrayList<>();
            for (Holder<Item> holder : BuiltInRegistries.ITEM.getTagOrEmpty(tag)) {
                result.add(holder.value());
            }
            return List.copyOf(result);
        }
        Item item = itemOf(raw);
        return item == null ? List.of() : List.of(item);
    }

    /** Tag turn-ins label through the vanilla tag translation key ({@code tag.ns.path}). */
    private static Component turnInLabel(String raw, List<Item> items) {
        if (raw.startsWith("#")) {
            return Component.translatable("tag." + tagNamespace(raw) + "." + tagPath(raw));
        }
        return items.isEmpty() ? Component.literal(raw) : items.getFirst().getDefaultInstance().getDisplayName();
    }

    private static int countTurnIn(ServerLevel world, ServerPlayer player,
                                   QuestDefinition.TurnInItem item, List<Item> items) {
        if (items.isEmpty()) {
            return 0;
        }
        if (items.size() == 1) {
            return DailyQuestService.countCompletionItem(world, player, items.getFirst());
        }
        return DailyQuestService.countCompletionItems(world, player, items.toArray(new Item[0]));
    }

    private static boolean consumeTurnIn(ServerLevel world, ServerPlayer player,
                                         QuestDefinition.TurnInItem item, List<Item> items) {
        int count = turnInCountStatic(item);
        if (items.isEmpty() || count <= 0) {
            return false;
        }
        if (items.size() == 1) {
            return DailyQuestService.consumeCompletionItem(world, player, items.getFirst(), count);
        }
        return DailyQuestService.consumeCompletionItems(world, player, count, items.toArray(new Item[0]));
    }

    private static int turnInCountStatic(QuestDefinition.TurnInItem item) {
        if (item.salt() == null) {
            return item.count();
        }
        return DailyQuestService.tunedTargetFor(item.count(), item.salt());
    }

    private boolean hasTurnInItems(ServerLevel world, ServerPlayer player) {
        for (QuestDefinition.TurnInItem item : definition.turnInItems()) {
            List<Item> resolved = turnInItemsOf(item.item());
            if (resolved.isEmpty()) {
                continue;
            }
            if (countTurnIn(world, player, item, resolved) < turnInCount(item)) {
                return false;
            }
        }
        return true;
    }

    private boolean turnInSatisfied(ServerLevel world, ServerPlayer player) {
        return definition.turnInItems().isEmpty() || hasTurnInItems(world, player);
    }

    @Override
    public DailyQuestCompletion buildCompletion(ServerLevel world) {
        Component line1;
        Component line2;
        Component line3;
        if (definition.usesLangKeys()) {
            List<String> keys = definition.completionKeys();
            line1 = componentOrEmpty(keys, 0);
            line2 = componentOrEmpty(keys, 1);
            line3 = componentOrEmpty(keys, 2);
        } else {
            List<String> lines = definition.completionLines(locale());
            line1 = literalOrEmpty(lines, 0);
            line2 = literalOrEmpty(lines, 1);
            line3 = literalOrEmpty(lines, 2);
        }
        // F1b: base rewards live in the definition — the first two direct item
        // entries map onto the classic rewardB/rewardC stacks.
        QuestDefinition.Rewards rewards = definition.rewards();
        if (rewards.items().size() > 2) {
            de.trakt.Trakt.LOGGER.warn(
                    "[trakt] quest '{}' authored {} direct reward items; the daily circuit displays at most 2",
                    definition.id(), rewards.items().size());
        }
        return DailyQuestService.buildCompletion(type(), title(), line1, line2, line3,
                rewards.silvermarks(), rewards.levels(), rewards.reputationTrack(), rewards.reputationAmount(),
                rewardStack(0), rewardStack(1));
    }

    private ItemStack rewardStack(int index) {
        List<QuestDefinition.RewardItem> items = definition.rewards().items();
        if (index >= items.size()) {
            return ItemStack.EMPTY;
        }
        Item item = itemOf(items.get(index).item());
        return item == null ? ItemStack.EMPTY : new ItemStack(item, items.get(index).count());
    }

    private Component componentOrEmpty(List<String> keys, int index) {
        if (index < keys.size()) {
            return Component.translatable(keys.get(index)).withStyle(ChatFormatting.GRAY);
        }
        return Component.empty();
    }

    private Component literalOrEmpty(List<String> lines, int index) {
        if (index < lines.size() && !lines.get(index).isBlank()) {
            return Component.literal(lines.get(index)).withStyle(ChatFormatting.GRAY);
        }
        return Component.empty();
    }

    // ------------------------------------------------------------------ hooks (registry-driven dispatch)

    @Override
    public void onAccepted(ServerLevel world, ServerPlayer player) {
        UUID playerId = player.getUUID();
        for (int i = 0; i < definition.goals().size(); i++) {
            QuestGoal goal = goal(i);
            if (!goal.kind().statDelta()) {
                continue;
            }
            if (goal.kind().statKey() != null) {
                DailyQuestService.setQuestInt(world, playerId, goal.baselineKey(definition.id()),
                        customStat(player, goal.kind().statKey()) + 1);
                continue;
            }
            Item item = itemOf(goal.item());
            if (item != null) {
                DailyQuestService.setQuestInt(world, playerId, goal.baselineKey(definition.id()),
                        DailyQuestService.getCraftedStat(player, item) + 1);
            }
        }
    }

    @Override
    public void onServerTick(ServerLevel world, ServerPlayer player) {
        if (!DailyQuestService.isTrackingQuest(world, player.getUUID(), type())) {
            return;
        }
        UUID playerId = player.getUUID();
        for (int i = 0; i < definition.goals().size(); i++) {
            QuestGoal goal = goal(i);
            if (!goal.kind().statDelta()) {
                continue;
            }
            String baselineKey = goal.baselineKey(definition.id());
            boolean customStat = goal.kind().statKey() != null;
            Item item = customStat ? null : itemOf(goal.item());
            if (!customStat && item == null) {
                continue;
            }
            int crafted = customStat ? customStat(player, goal.kind().statKey())
                    : DailyQuestService.getCraftedStat(player, item);
            if (!gateSatisfied(i, world, playerId)) {
                DailyQuestService.setQuestInt(world, playerId, baselineKey, crafted + 1);
                continue;
            }
            int storedBaseline = DailyQuestService.getQuestInt(world, playerId, baselineKey);
            if (storedBaseline == 0) {
                DailyQuestService.setQuestInt(world, playerId, baselineKey, crafted + 1);
                continue;
            }
            int delta = crafted - (storedBaseline - 1);
            if (delta > 0) {
                credit(world, player, i, delta);
            }
            DailyQuestService.setQuestInt(world, playerId, baselineKey, crafted + 1);
        }
    }

    /** Custom-stat read for {@code statKey} kinds (F2b catch_fish). */
    private static int customStat(ServerPlayer player, String statKey) {
        try {
            return DailyQuestService.getCustomStat(player, Identifier.parse(statKey));
        } catch (RuntimeException unknownStat) {
            return 0;
        }
    }

    @Override
    public void onBlockBreak(ServerLevel world, ServerPlayer player, BlockPos pos, BlockState state) {
        if (!DailyQuestService.isTrackingQuest(world, player.getUUID(), type())) {
            return;
        }
        for (int i = 0; i < definition.goals().size(); i++) {
            QuestGoal goal = goal(i);
            if (!goal.kind().listensTo(QuestGoalType.Hook.BLOCK_BREAK)) {
                continue;
            }
            if (matchesBlock(goal, state)) {
                credit(world, player, i, 1);
            }
        }
    }

    @Override
    public void onTrackedItemPickup(ServerLevel world, ServerPlayer player, ItemStack stack, int count) {
        if (!DailyQuestService.isTrackingQuest(world, player.getUUID(), type()) || count <= 0) {
            return;
        }
        for (int i = 0; i < definition.goals().size(); i++) {
            QuestGoal goal = goal(i);
            if (!goal.kind().listensTo(QuestGoalType.Hook.ITEM_PICKUP)) {
                continue;
            }
            if (matchesItem(goal.item(), stack)) {
                credit(world, player, i, count);
            }
        }
    }

    @Override
    public void onFurnaceOutput(ServerLevel world, ServerPlayer player, ItemStack stack) {
        if (!DailyQuestService.isTrackingQuest(world, player.getUUID(), type())) {
            return;
        }
        UUID playerId = player.getUUID();
        for (int i = 0; i < definition.goals().size(); i++) {
            QuestGoal goal = goal(i);
            if (!goal.kind().listensTo(QuestGoalType.Hook.FURNACE_OUTPUT)) {
                continue;
            }
            if (!gateSatisfied(i, world, playerId)) {
                continue;
            }
            if (matchesItem(goal.item(), stack)) {
                credit(world, player, i, stack.getCount());
            }
        }
    }

    @Override
    public void onMonsterKill(ServerLevel world, ServerPlayer player, Entity killedEntity) {
        if (!DailyQuestService.isTrackingQuest(world, player.getUUID(), type())) {
            return;
        }
        for (int i = 0; i < definition.goals().size(); i++) {
            QuestGoal goal = goal(i);
            if (!goal.kind().listensTo(QuestGoalType.Hook.MONSTER_KILL)) {
                continue;
            }
            if (matchesEntity(goal, killedEntity)) {
                credit(world, player, i, 1);
            }
        }
    }

    @Override
    public void onSheepSheared(ServerLevel world, ServerPlayer player, Sheep sheep) {
        if (!DailyQuestService.isTrackingQuest(world, player.getUUID(), type())) {
            return;
        }
        for (int i = 0; i < definition.goals().size(); i++) {
            QuestGoal goal = goal(i);
            if (!goal.kind().listensTo(QuestGoalType.Hook.SHEEP_SHEARED)) {
                continue;
            }
            credit(world, player, i, 1);
        }
    }

    @Override
    public void onVillagerTrade(ServerLevel world, ServerPlayer player, ItemStack stack) {
        if (!DailyQuestService.isTrackingQuest(world, player.getUUID(), type())) {
            return;
        }
        for (int i = 0; i < definition.goals().size(); i++) {
            QuestGoal goal = goal(i);
            if (!goal.kind().listensTo(QuestGoalType.Hook.VILLAGER_TRADE)) {
                continue;
            }
            if (goal.item() == null) {
                credit(world, player, i, 1);
            } else if (matchesItem(goal.item(), stack)) {
                credit(world, player, i, stack.getCount());
            }
        }
    }

    @Override
    public void onAnimalLove(ServerLevel world, ServerPlayer player, Animal animal) {
        if (!DailyQuestService.isTrackingQuest(world, player.getUUID(), type())) {
            return;
        }
        for (int i = 0; i < definition.goals().size(); i++) {
            QuestGoal goal = goal(i);
            if (!goal.kind().listensTo(QuestGoalType.Hook.ANIMAL_LOVE)) {
                continue;
            }
            if (goal.entity() == null || matchesEntity(goal, animal)) {
                credit(world, player, i, 1);
            }
        }
    }

    /** F2b harvest_hive: a full hive/nest harvested with bottle (honey) or shears (comb). */
    @Override
    public void onBeeNestInteract(ServerLevel world, ServerPlayer player, BlockState state, ItemStack inHand) {
        if (!DailyQuestService.isTrackingQuest(world, player.getUUID(), type())) {
            return;
        }
        Item yield = hiveYield(state, inHand == null ? null : inHand.getItem());
        if (yield == null) {
            return;
        }
        for (int i = 0; i < definition.goals().size(); i++) {
            QuestGoal goal = goal(i);
            if (!goal.kind().listensTo(QuestGoalType.Hook.BEE_NEST_INTERACT)) {
                continue;
            }
            if (matchesItem(goal.item(), new ItemStack(yield))) {
                credit(world, player, i, 1);
            }
        }
    }

    /** Same yield rule the retired HoneyDailyQuest applied: full hive + bottle/shears. */
    private static Item hiveYield(BlockState state, Item item) {
        if (state == null || item == null || !state.is(Blocks.BEE_NEST) && !state.is(Blocks.BEEHIVE)) {
            return null;
        }
        if (!state.hasProperty(BeehiveBlock.HONEY_LEVEL)
                || state.getValue(BeehiveBlock.HONEY_LEVEL) < BeehiveBlock.MAX_HONEY_LEVELS) {
            return null;
        }
        if (item == Items.GLASS_BOTTLE) {
            return Items.HONEY_BOTTLE;
        }
        if (item == Items.SHEARS) {
            return Items.HONEYCOMB;
        }
        return null;
    }

    /** F2b visit_villagers: distinct adult villagers with a tradeable profession. */
    @Override
    public void onEntityUse(ServerLevel world, ServerPlayer player, Entity entity, ItemStack inHand) {
        if (!DailyQuestService.isTrackingQuest(world, player.getUUID(), type())) {
            return;
        }
        boolean hasVisitGoal = false;
        for (QuestGoal goal : definition.goals()) {
            if (goal.kind().listensTo(QuestGoalType.Hook.ENTITY_USE)) {
                hasVisitGoal = true;
                break;
            }
        }
        if (!hasVisitGoal || !(entity instanceof Villager villager) || villager.isBaby()) {
            return;
        }
        Holder<VillagerProfession> profession = villager.getVillagerData().profession();
        if (profession.is(VillagerProfession.NONE) || profession.is(VillagerProfession.NITWIT)) {
            return;
        }
        UUID playerId = player.getUUID();
        for (int i = 0; i < definition.goals().size(); i++) {
            QuestGoal goal = goal(i);
            if (!goal.kind().listensTo(QuestGoalType.Hook.ENTITY_USE)) {
                continue;
            }
            // distinctness: one visit per villager, stored as a per-goal quest flag
            String visitFlag = progressKey(i) + ".visited." + entity.getStringUUID();
            if (DailyQuestService.hasQuestFlag(world, playerId, visitFlag)) {
                continue;
            }
            DailyQuestService.setQuestFlag(world, playerId, visitFlag, true);
            credit(world, player, i, 1);
        }
    }

    /** F2b recolor_collar: wolf/cat collar recolor succeeded (WolfMixin/CatMixin). */
    @Override
    public void onCollarRecolor(ServerLevel world, ServerPlayer player) {
        if (!DailyQuestService.isTrackingQuest(world, player.getUUID(), type())) {
            return;
        }
        for (int i = 0; i < definition.goals().size(); i++) {
            QuestGoal goal = goal(i);
            if (!goal.kind().listensTo(QuestGoalType.Hook.COLLAR_RECOLOR)) {
                continue;
            }
            credit(world, player, i, 1);
        }
    }

    /** F2b part 2 tame_animal: taming succeeded, optionally filtered by entity reference. */
    @Override
    public void onAnimalTamed(ServerLevel world, ServerPlayer player, TamableAnimal animal) {
        if (!DailyQuestService.isTrackingQuest(world, player.getUUID(), type())) {
            return;
        }
        for (int i = 0; i < definition.goals().size(); i++) {
            QuestGoal goal = goal(i);
            if (!goal.kind().listensTo(QuestGoalType.Hook.ANIMAL_TAMED)) {
                continue;
            }
            if (goal.entity() == null || matchesEntity(goal, animal)) {
                credit(world, player, i, 1);
            }
        }
    }

    /** F2b part 2 enchant_item: anvil combine produced a newly enchanted item. */
    @Override
    public void onItemEnchanted(ServerLevel world, ServerPlayer player, ItemStack output) {
        if (!DailyQuestService.isTrackingQuest(world, player.getUUID(), type())) {
            return;
        }
        for (int i = 0; i < definition.goals().size(); i++) {
            QuestGoal goal = goal(i);
            if (!goal.kind().listensTo(QuestGoalType.Hook.ITEM_ENCHANTED)) {
                continue;
            }
            if (goal.item() == null || matchesItem(goal.item(), output)) {
                credit(world, player, i, 1);
            }
        }
    }

    /** F2b part 2 brew_potion: every finished brewing counts (like the shard trial). */
    @Override
    public void onPotionBrewed(ServerLevel world, ServerPlayer player) {
        if (!DailyQuestService.isTrackingQuest(world, player.getUUID(), type())) {
            return;
        }
        for (int i = 0; i < definition.goals().size(); i++) {
            QuestGoal goal = goal(i);
            if (goal.kind().listensTo(QuestGoalType.Hook.POTION_BREWED)) {
                credit(world, player, i, 1);
            }
        }
    }

    /** F2b part 2 visit_village: distinct villages, flagged per stable village identity. */
    @Override
    public void onVillageVisited(ServerLevel world, ServerPlayer player, String villageKey) {
        if (!DailyQuestService.isTrackingQuest(world, player.getUUID(), type())) {
            return;
        }
        UUID playerId = player.getUUID();
        for (int i = 0; i < definition.goals().size(); i++) {
            QuestGoal goal = goal(i);
            if (!goal.kind().listensTo(QuestGoalType.Hook.VILLAGE_VISITED)) {
                continue;
            }
            String visitFlag = progressKey(i) + ".visited." + villageKey;
            if (DailyQuestService.hasQuestFlag(world, playerId, visitFlag)) {
                continue;
            }
            DailyQuestService.setQuestFlag(world, playerId, visitFlag, true);
            credit(world, player, i, 1);
        }
    }

    /** F2b part 2 escort_caravan: guild convoy arrival witnessed in person. */
    @Override
    public void onCaravanEscorted(ServerLevel world, ServerPlayer player) {
        if (!DailyQuestService.isTrackingQuest(world, player.getUUID(), type())) {
            return;
        }
        for (int i = 0; i < definition.goals().size(); i++) {
            QuestGoal goal = goal(i);
            if (goal.kind().listensTo(QuestGoalType.Hook.CARAVAN_ESCORTED)) {
                credit(world, player, i, 1);
            }
        }
    }

    /** F2b part 2 deliver_item: supplied freight contract arrived, cargo optional filter. */
    @Override
    public void onItemDelivered(ServerLevel world, ServerPlayer player, ItemStack cargo) {
        if (!DailyQuestService.isTrackingQuest(world, player.getUUID(), type())) {
            return;
        }
        for (int i = 0; i < definition.goals().size(); i++) {
            QuestGoal goal = goal(i);
            if (!goal.kind().listensTo(QuestGoalType.Hook.ITEM_DELIVERED)) {
                continue;
            }
            if (goal.item() == null || matchesItem(goal.item(), cargo)) {
                credit(world, player, i, 1);
            }
        }
    }

    // ------------------------------------------------------------------ credit

    private void credit(ServerLevel world, ServerPlayer player, int index, int amount) {
        if (amount <= 0) {
            return;
        }
        if (!gateSatisfied(index, world, player.getUUID())) {
            return;
        }
        // F2b part 2: context modifiers gate every credit of the leaf uniformly.
        if (!modifiersSatisfied(goal(index), world, player)) {
            return;
        }
        UUID playerId = player.getUUID();
        int target = storedTarget(index, world, playerId);
        int current = progress(index, world, playerId);
        if (current >= target) {
            return;
        }
        DailyQuestService.setQuestInt(world, playerId, progressKey(index), Math.min(target, current + amount));
        DailyQuestService.completeIfEligible(world, player);
        DailyQuestService.sendCurrentProgressActionbar(world, player);
    }

    // ------------------------------------------------------------------ modifiers

    /**
     * F2b part 2: {@code at_night}/{@code raining} must all hold; several
     * {@code in_biome} references are OR-ed among themselves. Night follows the
     * same overworld-clock window the rest of the mod treats as night.
     */
    private static boolean modifiersSatisfied(QuestGoal goal, ServerLevel world, ServerPlayer player) {
        boolean biomeRequired = false;
        boolean biomeMatched = false;
        for (String modifier : goal.modifiers()) {
            if ("at_night".equals(modifier)) {
                if (!isNight(world)) {
                    return false;
                }
            } else if ("raining".equals(modifier)) {
                if (!world.isRaining()) {
                    return false;
                }
            } else if (modifier.startsWith("in_biome:")) {
                biomeRequired = true;
                if (inBiome(world, player, modifier.substring("in_biome:".length()))) {
                    biomeMatched = true;
                }
            }
        }
        return !biomeRequired || biomeMatched;
    }

    /** Same night window the pilgrim contracts and trade-road encounters use. */
    private static boolean isNight(ServerLevel world) {
        if (world == null) {
            return false;
        }
        long dayTime = Math.floorMod(world.getOverworldClockTime(), 24000L);
        return dayTime >= 13000L && dayTime <= 23000L;
    }

    /** Biome membership at the player position: plain identifier or {@code #} tag. */
    private static boolean inBiome(ServerLevel world, ServerPlayer player, String ref) {
        Holder<Biome> biome = world.getBiome(player.blockPosition());
        if (ref.startsWith("#")) {
            TagKey<Biome> tag = TagKey.create(Registries.BIOME,
                    Identifier.fromNamespaceAndPath(tagNamespace(ref), tagPath(ref)));
            return biome.is(tag);
        }
        return biome.unwrapKey().map(key -> key.identifier().toString().equals(ref)).orElse(false);
    }

    // ------------------------------------------------------------------ matching

    private boolean matchesBlock(QuestGoal goal, BlockState state) {
        String raw = goal.block();
        if (raw == null) {
            return false;
        }
        if (goal.isTagReference(raw)) {
            TagKey<Block> tag = TagKey.create(Registries.BLOCK,
                    Identifier.fromNamespaceAndPath(goal.tagNamespace(raw), goal.tagPath(raw)));
            return state.is(tag);
        }
        Block block = blockOf(raw);
        return block != null && state.is(block);
    }

    private boolean matchesEntity(QuestGoal goal, Entity entity) {
        String raw = goal.entity();
        if (raw == null) {
            return false;
        }
        if (goal.isTagReference(raw)) {
            TagKey<EntityType<?>> tag = TagKey.create(Registries.ENTITY_TYPE,
                    Identifier.fromNamespaceAndPath(goal.tagNamespace(raw), goal.tagPath(raw)));
            return isInEntityTag(entity.getType(), tag);
        }
        EntityType<?> type = entityTypeOf(raw);
        return type != null && entity.getType() == type;
    }

    /** Tag membership for an entity type through the registry's resolved tag sets. */
    private static boolean isInEntityTag(EntityType<?> type, TagKey<EntityType<?>> tag) {
        for (Holder<EntityType<?>> holder : BuiltInRegistries.ENTITY_TYPE.getTagOrEmpty(tag)) {
            if (holder.value() == type) {
                return true;
            }
        }
        return false;
    }

    private boolean matchesItem(String raw, ItemStack stack) {
        if (raw == null || stack == null || stack.isEmpty()) {
            return false;
        }
        if (raw.startsWith("#")) {
            TagKey<Item> tag = TagKey.create(Registries.ITEM,
                    Identifier.fromNamespaceAndPath(tagNamespace(raw), tagPath(raw)));
            return stack.is(tag);
        }
        Item item = itemOf(raw);
        return item != null && stack.is(item);
    }

    // ------------------------------------------------------------------ registry lookups

    private static Item itemOf(String rawId) {
        if (rawId == null || rawId.isBlank() || rawId.startsWith("#")) {
            return null;
        }
        try {
            Identifier id = Identifier.parse(rawId);
            if (!BuiltInRegistries.ITEM.containsKey(id)) {
                return null;
            }
            return BuiltInRegistries.ITEM.getValue(id);
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private static Block blockOf(String rawId) {
        if (rawId == null || rawId.isBlank() || rawId.startsWith("#")) {
            return null;
        }
        try {
            Identifier id = Identifier.parse(rawId);
            if (!BuiltInRegistries.BLOCK.containsKey(id)) {
                return null;
            }
            return BuiltInRegistries.BLOCK.getValue(id);
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private static EntityType<?> entityTypeOf(String rawId) {
        if (rawId == null || rawId.isBlank() || rawId.startsWith("#")) {
            return null;
        }
        try {
            Identifier id = Identifier.parse(rawId);
            if (!BuiltInRegistries.ENTITY_TYPE.containsKey(id)) {
                return null;
            }
            return BuiltInRegistries.ENTITY_TYPE.getValue(id);
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private static String tagNamespace(String raw) {
        String body = raw.substring(1);
        return body.contains(":") ? body.substring(0, body.indexOf(':')) : "minecraft";
    }

    private static String tagPath(String raw) {
        String body = raw.substring(1);
        return body.contains(":") ? body.substring(body.indexOf(':') + 1) : body;
    }
}
