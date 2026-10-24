package com.andryushka.arrow.registry;

import com.andryushka.arrow.AndryushkaArrowMod;
import net.minecraft.entity.Entity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.damage.DamageType;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

/**
 * Свой тип урона (data/andryushka_arrow/damage_type). Он намеренно не входит в тег is_projectile:
 * иначе эндермены уворачивались бы телепортом, а это волшебная стрела — от неё не уйти.
 */
public final class ModDamageTypes {
    public static final RegistryKey<DamageType> ANDRYUSHKA_ARROW =
            RegistryKey.of(RegistryKeys.DAMAGE_TYPE, AndryushkaArrowMod.id("andryushka_arrow"));

    private ModDamageTypes() {
    }

    /** Если тип урона из данных мода почему-то не загрузился, бьём как обычный снаряд, а не падаем. */
    public static DamageSource arrow(World world, Entity arrow, @Nullable Entity attacker) {
        return world.getRegistryManager().get(RegistryKeys.DAMAGE_TYPE).getEntry(ANDRYUSHKA_ARROW)
                .map(type -> new DamageSource(type, arrow, attacker))
                .orElseGet(() -> world.getDamageSources().thrown(arrow, attacker));
    }
}
