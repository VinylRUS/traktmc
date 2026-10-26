package de.trakt.relay;

import com.mojang.serialization.DynamicOps;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.RegistryOps;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;

/**
 * Item plumbing for relay shipments. Stacks are always persisted in their fully
 * encoded form (count + components) so enchantments, custom names and potion
 * contents survive the trip — the single encoded copy is the escrow itself.
 */
public final class RelayItems {
    static final String NO_ITEM = "minecraft:air";

    private RelayItems() {}

    public static ListTag encode(HolderLookup.Provider registries, List<ItemStack> items) {
        DynamicOps<Tag> ops = RegistryOps.create(NbtOps.INSTANCE, registries);
        ListTag list = new ListTag();
        for (ItemStack stack : items) {
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            list.add(ItemStack.CODEC.encodeStart(ops, stack).getOrThrow());
        }
        return list;
    }

    public static List<ItemStack> decode(HolderLookup.Provider registries, ListTag tag) {
        List<ItemStack> items = new ArrayList<>();
        if (tag == null) {
            return items;
        }
        DynamicOps<Tag> ops = RegistryOps.create(NbtOps.INSTANCE, registries);
        for (Tag entry : tag) {
            ItemStack stack = ItemStack.CODEC.parse(ops, entry).result().orElse(ItemStack.EMPTY);
            if (!stack.isEmpty()) {
                items.add(stack);
            }
        }
        return items;
    }

    /** True when the stack can join the slots: merged into a compatible stack or a free slot exists. */
    public static boolean canInsert(List<ItemStack> slots, ItemStack stack, int maxSlots) {
        if (stack == null || stack.isEmpty()) {
            return true;
        }
        int maxStackSize = stack.getMaxStackSize();
        for (ItemStack slot : slots) {
            if (ItemStack.isSameItemSameComponents(slot, stack) && slot.getCount() < maxStackSize) {
                return true;
            }
        }
        return slots.size() < maxSlots;
    }

    /** Inserts the whole stack (merge first, then a new slot); mutates the passed stack to zero on success. */
    public static void insert(List<ItemStack> slots, ItemStack stack, int maxSlots) {
        if (stack == null || stack.isEmpty()) {
            return;
        }
        int maxStackSize = stack.getMaxStackSize();
        for (ItemStack slot : slots) {
            if (ItemStack.isSameItemSameComponents(slot, stack) && slot.getCount() < maxStackSize) {
                int room = maxStackSize - slot.getCount();
                int moved = Math.min(room, stack.getCount());
                slot.grow(moved);
                stack.shrink(moved);
                if (stack.isEmpty()) {
                    return;
                }
            }
        }
        if (slots.size() < maxSlots) {
            slots.add(stack.copy());
            stack.setCount(0);
        }
    }

    /** Compact human-readable summary used in chat and panel events: "iron_ingot x32, bread x5". */
    public static String summarize(List<ItemStack> items) {
        if (items == null || items.isEmpty()) {
            return "-";
        }
        StringBuilder builder = new StringBuilder();
        for (ItemStack stack : items) {
            if (builder.length() > 0) {
                builder.append(", ");
            }
            builder.append(itemId(stack)).append(" x").append(stack.getCount());
        }
        return builder.toString();
    }

    static String itemId(ItemStack stack) {
        Identifier id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        return id == null ? NO_ITEM : id.toString();
    }
}
