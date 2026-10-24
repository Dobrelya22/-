package com.olegus.arrow.registry;

import com.olegus.arrow.OlegusArrowMod;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.stat.StatFormatter;
import net.minecraft.stat.Stats;
import net.minecraft.util.Identifier;

/** Своя строка в статистике игрока: «Побед Стрелой Олегуса». */
public final class ModStats {
    public static final Identifier ARROW_KILLS = OlegusArrowMod.id("arrow_kills");

    private ModStats() {
    }

    public static void init() {
        Registry.register(Registries.CUSTOM_STAT, ARROW_KILLS, ARROW_KILLS);
        Stats.CUSTOM.getOrCreateStat(ARROW_KILLS, StatFormatter.DEFAULT);
    }
}
