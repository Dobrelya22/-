package com.andryushka.arrow.registry;

import com.andryushka.arrow.AndryushkaArrowMod;
import net.minecraft.advancement.AdvancementEntry;
import net.minecraft.server.network.ServerPlayerEntity;

/**
 * Достижения на вкладке «Стрела Андрюшки» (data/andryushka_arrow/advancement). У каждого, кроме
 * корневого, условие minecraft:impossible: его выдаёт сам мод в нужный момент.
 */
public final class ModAdvancements {
    public static final String LAUNCH = "launch";
    public static final String WHISTLE = "whistle";
    public static final String BOOST = "boost";
    public static final String UNSEEN_KILL = "unseen_kill";
    public static final String STREAK_10 = "streak_10";
    public static final String STREAK_25 = "streak_25";
    public static final String LOOT = "loot";
    private static final String CRITERION = "done";

    private ModAdvancements() {
    }

    public static void grant(ServerPlayerEntity player, String name) {
        AdvancementEntry advancement = player.server.getAdvancementLoader().get(AndryushkaArrowMod.id(name));
        if (advancement != null) {
            player.getAdvancementTracker().grantCriterion(advancement, CRITERION);
        }
    }

    public static boolean isDone(ServerPlayerEntity player, String name) {
        AdvancementEntry advancement = player.server.getAdvancementLoader().get(AndryushkaArrowMod.id(name));
        return advancement != null && player.getAdvancementTracker().getProgress(advancement).isDone();
    }
}
