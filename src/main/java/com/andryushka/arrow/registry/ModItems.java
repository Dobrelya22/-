package com.andryushka.arrow.registry;

import com.andryushka.arrow.AndryushkaArrowMod;
import com.andryushka.arrow.item.AndryushkaArrowItem;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.minecraft.item.Item;
import net.minecraft.item.ItemGroups;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.util.Rarity;

public final class ModItems {
    public static final Item ANDRYUSHKA_ARROW = Registry.register(
            Registries.ITEM,
            AndryushkaArrowMod.id("andryushka_arrow"),
            new AndryushkaArrowItem(new Item.Settings().maxCount(1).rarity(Rarity.EPIC).fireproof()));

    private ModItems() {
    }

    public static void init() {
        ItemGroupEvents.modifyEntriesEvent(ItemGroups.COMBAT).register(entries -> entries.add(ANDRYUSHKA_ARROW));
    }
}
