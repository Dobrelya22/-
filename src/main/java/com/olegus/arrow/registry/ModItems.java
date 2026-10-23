package com.olegus.arrow.registry;

import com.olegus.arrow.OlegusArrowMod;
import com.olegus.arrow.item.OlegusArrowItem;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.minecraft.item.Item;
import net.minecraft.item.ItemGroups;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.util.Rarity;

public final class ModItems {
    public static final Item OLEGUS_ARROW = Registry.register(
            Registries.ITEM,
            OlegusArrowMod.id("olegus_arrow"),
            new OlegusArrowItem(new Item.Settings().maxCount(1).rarity(Rarity.EPIC).fireproof()));

    private ModItems() {
    }

    public static void init() {
        ItemGroupEvents.modifyEntriesEvent(ItemGroups.COMBAT).register(entries -> entries.add(OLEGUS_ARROW));
    }
}
