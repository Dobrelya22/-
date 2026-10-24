package com.andryushka.arrow.registry;

import com.andryushka.arrow.AndryushkaArrowMod;
import com.andryushka.arrow.entity.AndryushkaArrowEntity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnGroup;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;

public final class ModEntities {
    public static final EntityType<AndryushkaArrowEntity> ANDRYUSHKA_ARROW = Registry.register(
            Registries.ENTITY_TYPE,
            AndryushkaArrowMod.id("andryushka_arrow"),
            EntityType.Builder.<AndryushkaArrowEntity>create(AndryushkaArrowEntity::new, SpawnGroup.MISC)
                    .dimensions(0.5F, 0.5F)
                    .maxTrackingRange(8)
                    // Позиция уходит клиентам каждый тик, чтобы полёт был плавным.
                    .trackingTickInterval(1)
                    .makeFireImmune()
                    .build("andryushka_arrow"));

    private ModEntities() {
    }

    public static void init() {
    }
}
