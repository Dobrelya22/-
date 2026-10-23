package com.olegus.arrow;

import com.olegus.arrow.network.ArrowNetworking;
import com.olegus.arrow.registry.ModAttachments;
import com.olegus.arrow.registry.ModEntities;
import com.olegus.arrow.registry.ModItems;
import com.olegus.arrow.registry.ModSounds;
import net.fabricmc.api.ModInitializer;
import net.minecraft.util.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class OlegusArrowMod implements ModInitializer {
    public static final String MOD_ID = "olegus_arrow";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    public static Identifier id(String path) {
        return Identifier.of(MOD_ID, path);
    }

    @Override
    public void onInitialize() {
        ModSounds.init();
        ModEntities.init();
        ModItems.init();
        ModAttachments.init();
        ArrowNetworking.init();
        ArrowControl.init();
        LOGGER.info("Стрела Олегуса готова к полёту");
    }
}
