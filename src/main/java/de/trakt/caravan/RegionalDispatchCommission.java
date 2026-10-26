package de.trakt.caravan;

import de.trakt.shrine.VillageBondType;
import java.util.Locale;
import net.minecraft.network.chat.Component;

/** Names a shipment from the stored source and destination identities. */
final class RegionalDispatchCommission {
    private RegionalDispatchCommission() {}

    static String key(VillageBondType source, VillageBondType target) {
        return (source == target ? "same_" : "to_") + target.name().toLowerCase(Locale.ROOT);
    }

    static Component title(VillageBondType source, VillageBondType target) {
        return Component.translatable("screen.trakt.dispatch.commission." + key(source, target));
    }
}
