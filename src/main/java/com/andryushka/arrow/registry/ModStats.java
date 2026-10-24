package com.andryushka.arrow.registry;

import com.andryushka.arrow.AndryushkaArrowMod;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.stat.StatFormatter;
import net.minecraft.stat.Stats;
import net.minecraft.util.Identifier;

/** Своя строка в статистике игрока: «Побед Стрелой Андрюшки». */
public final class ModStats {
    public static final Identifier ARROW_KILLS = AndryushkaArrowMod.id("arrow_kills");

    private ModStats() {
    }

    public static void init() {
        Registry.register(Registries.CUSTOM_STAT, ARROW_KILLS, ARROW_KILLS);
        Stats.CUSTOM.getOrCreateStat(ARROW_KILLS, StatFormatter.DEFAULT);
    }
}
