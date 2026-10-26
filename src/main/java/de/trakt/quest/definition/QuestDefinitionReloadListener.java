package de.trakt.quest.definition;

import de.trakt.Trakt;
import net.fabricmc.fabric.api.resource.v1.reloader.SimpleReloadListener;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackType;
import net.fabricmc.fabric.api.resource.v1.ResourceLoader;

/**
 * Bridges the unified quest definition registry into the vanilla data pack reload
 * (F1a): every server start and every {@code /reload} re-reads all
 * {@code data/<namespace>/quests/*.json} resources and rebuilds the merged view.
 *
 * <p>The world folder layer is attached by the server started hook
 * (see {@code Trakt#onInitialize}) because the level storage is not yet
 * available while data packs load.
 */
public final class QuestDefinitionReloadListener extends SimpleReloadListener<QuestDefinitionRegistry.ReloadReport> {

    public static final Identifier IDENTIFIER = Identifier.fromNamespaceAndPath(Trakt.MOD_ID, "quest_definitions");

    /** Registers the listener on the server data pack stack (called once from the mod initializer). */
    public static void register() {
        ResourceLoader.get(PackType.SERVER_DATA).registerReloadListener(IDENTIFIER, new QuestDefinitionReloadListener());
    }

    private QuestDefinitionReloadListener() {}

    @Override
    protected QuestDefinitionRegistry.ReloadReport prepare(SharedState state) {
        return QuestDefinitionRegistry.get().reloadFromResources(state.resourceManager(), null);
    }

    @Override
    protected void apply(QuestDefinitionRegistry.ReloadReport report, SharedState state) {
        Trakt.LOGGER.info("[quests] data pack layer reloaded: {} definition(s) ({} builtin, {} datapack, {} world, {} api, {} disabled)",
                report.total(), report.builtin(), report.datapack(), report.world(), report.api(), report.disabled());
        for (java.util.Map.Entry<String, String> problem : report.problems().entrySet()) {
            Trakt.LOGGER.warn("[quests] problem in {}: {}", problem.getKey(), problem.getValue());
        }
        QuestDefinitionRegistry.get().emitChangeEvent();
    }

    @Override
    public String getName() {
        return "trakt:quest_definitions";
    }
}
