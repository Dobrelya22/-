package com.andryushka.arrow.registry;

import com.andryushka.arrow.AndryushkaArrowMod;
import com.andryushka.arrow.ArrowTrail;
import java.util.UUID;
import net.minecraft.component.ComponentType;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.util.Uuids;
import net.minecraft.util.dynamic.Codecs;

/** Что стрела помнит о себе в предмете: победы за всё время, окраску следа и свой номер. */
public final class ModComponents {
    /** Победы за всё время — по ним растёт уровень. */
    public static final ComponentType<Integer> KILLS = register("kills",
            ComponentType.<Integer>builder().codec(Codecs.NONNEGATIVE_INT).packetCodec(PacketCodecs.VAR_INT).build());
    public static final ComponentType<ArrowTrail> TRAIL = register("trail",
            ComponentType.<ArrowTrail>builder().codec(ArrowTrail.CODEC).packetCodec(ArrowTrail.PACKET_CODEC).build());
    /**
     * Номер стрелы. В творческом режиме предмет остаётся в руке, а летит его копия — по номеру
     * её победы потом записываются в тот самый предмет.
     */
    public static final ComponentType<UUID> ARROW_ID = register("arrow_id",
            ComponentType.<UUID>builder().codec(Uuids.CODEC).packetCodec(Uuids.PACKET_CODEC).build());

    private ModComponents() {
    }

    private static <T> ComponentType<T> register(String name, ComponentType<T> type) {
        return Registry.register(Registries.DATA_COMPONENT_TYPE, AndryushkaArrowMod.id(name), type);
    }

    public static void init() {
        // Компоненты регистрируются при загрузке класса.
    }
}
