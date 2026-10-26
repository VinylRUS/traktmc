package de.trakt.questmaster;

import de.trakt.quest.QuestCompletionMode;

/** Keeps an accepted hand-in visible without weakening server-side validation. */
final class QuestMasterTurnInPolicy {
    record Decision(boolean visible, boolean claimAction, boolean enabled, String labelKey) {}

    private QuestMasterTurnInPolicy() {}

    static Decision decide(QuestCompletionMode completionMode, boolean ready, boolean blockedByItems) {
        if (ready) {
            boolean turnIn = completionMode == QuestCompletionMode.QUESTMASTER_TURN_IN;
            return new Decision(true, true, true, turnIn
                    ? "screen.trakt.questmaster.action.turn_in"
                    : "screen.trakt.questmaster.action.claim");
        }
        if (completionMode == QuestCompletionMode.QUESTMASTER_TURN_IN) {
            return new Decision(true, blockedByItems, blockedByItems, blockedByItems
                    ? "screen.trakt.questmaster.action.turn_in"
                    : "screen.trakt.questmaster.action.in_progress");
        }
        return new Decision(false, false, false, "");
    }
}
