package com.olegus.arrow.registry;

import com.olegus.arrow.OlegusArrowMod;
import com.olegus.arrow.entity.OlegusArrowEntity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnGroup;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;

public final class ModEntities {
    public static final EntityType<OlegusArrowEntity> OLEGUS_ARROW = Registry.register(
            Registries.ENTITY_TYPE,
            OlegusArrowMod.id("olegus_arrow"),
            EntityType.Builder.<OlegusArrowEntity>create(OlegusArrowEntity::new, SpawnGroup.MISC)
                    .dimensions(0.5F, 0.5F)
                    .maxTrackingRange(8)
                    // Позиция уходит клиентам каждый тик, чтобы полёт был плавным.
                    .trackingTickInterval(1)
                    .makeFireImmune()
                    .build("olegus_arrow"));

    private ModEntities() {
    }

    public static void init() {
    }
}
