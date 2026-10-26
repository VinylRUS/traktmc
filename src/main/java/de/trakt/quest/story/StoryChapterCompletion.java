package de.trakt.quest.story;

import de.trakt.reputation.ReputationService;
import net.minecraft.network.chat.Component;

public record StoryChapterCompletion(
        Component title,
        Component completionLine1,
        Component completionLine2,
        Component completionLine3,
        long currencyReward,
        int levels,
        ReputationService.ReputationTrack reputationTrack,
        int reputationAmount,
        VillageProjectType unlockedProject
) {
}
