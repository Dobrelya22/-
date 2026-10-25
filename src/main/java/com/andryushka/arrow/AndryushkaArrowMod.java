package com.andryushka.arrow;

import com.andryushka.arrow.network.ArrowNetworking;
import com.andryushka.arrow.registry.ModAttachments;
import com.andryushka.arrow.registry.ModComponents;
import com.andryushka.arrow.registry.ModEntities;
import com.andryushka.arrow.registry.ModGameRules;
import com.andryushka.arrow.registry.ModItems;
import com.andryushka.arrow.registry.ModRecipes;
import com.andryushka.arrow.registry.ModSounds;
import com.andryushka.arrow.registry.ModStats;
import net.fabricmc.api.ModInitializer;
import net.minecraft.util.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AndryushkaArrowMod implements ModInitializer {
    public static final String MOD_ID = "andryushka_arrow";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    public static Identifier id(String path) {
        return Identifier.of(MOD_ID, path);
    }

    @Override
    public void onInitialize() {
        ModSounds.init();
        ModComponents.init();
        ModEntities.init();
        ModItems.init();
        ModAttachments.init();
        ModGameRules.init();
        ModStats.init();
        ModRecipes.init();
        ArrowNetworking.init();
        ArrowControl.init();
        LootDelivery.init();
        LOGGER.info("Стрела Андрюшки готова к полёту");
    }
}
