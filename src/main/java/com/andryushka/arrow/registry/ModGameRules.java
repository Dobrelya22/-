package com.andryushka.arrow.registry;

import com.andryushka.arrow.AndryushkaArrowMod;
import net.fabricmc.fabric.api.gamerule.v1.CustomGameRuleCategory;
import net.fabricmc.fabric.api.gamerule.v1.GameRuleFactory;
import net.fabricmc.fabric.api.gamerule.v1.GameRuleRegistry;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.world.GameRules;

/**
 * Настройки мода — правила игры: их видно в своём разделе при создании мира и можно менять
 * командой /gamerule, например {@code /gamerule andryushkaArrowDuration 90}.
 */
public final class ModGameRules {
    private static final CustomGameRuleCategory CATEGORY = new CustomGameRuleCategory(AndryushkaArrowMod.id("rules"),
            Text.translatable("gamerule.category.andryushka_arrow").formatted(Formatting.LIGHT_PURPLE, Formatting.BOLD));

    /** Сколько секунд стрела охотится, прежде чем закружить вокруг владельца и упасть. */
    public static final GameRules.Key<GameRules.IntRule> DURATION = GameRuleRegistry.register(
            "andryushkaArrowDuration", CATEGORY, GameRuleFactory.createIntRule(40, 10, 600));
    /** Урон одного удара (2 — одно сердечко). От скорости и ускорения в толпе не зависит. */
    public static final GameRules.Key<GameRules.IntRule> DAMAGE = GameRuleRegistry.register(
            "andryushkaArrowDamage", CATEGORY, GameRuleFactory.createIntRule(8, 1, 100));
    /** Добыча и опыт сражённых стрелой мобов сами прилетают к владельцу. */
    public static final GameRules.Key<GameRules.BooleanRule> BRINGS_LOOT = GameRuleRegistry.register(
            "andryushkaArrowBringsLoot", CATEGORY, GameRuleFactory.createBooleanRule(true));
    /** В режиме «все мобы» не трогать жителей, големов, приручённых и мобов с именем. */
    public static final GameRules.Key<GameRules.BooleanRule> SPARES_FRIENDS = GameRuleRegistry.register(
            "andryushkaArrowSparesFriends", CATEGORY, GameRuleFactory.createBooleanRule(true));

    private ModGameRules() {
    }

    public static void init() {
        // Правила регистрируются при загрузке класса.
    }
}
