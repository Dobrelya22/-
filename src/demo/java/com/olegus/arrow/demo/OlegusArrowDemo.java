package com.olegus.arrow.demo;

import com.olegus.arrow.OlegusArrowMod;
import com.olegus.arrow.client.ArrowKeyBindings;
import com.olegus.arrow.registry.ModItems;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.option.Perspective;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.entity.ItemEntity;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.resource.DataConfiguration;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.Difficulty;
import net.minecraft.world.GameMode;
import net.minecraft.world.GameRules;
import net.minecraft.world.gen.GeneratorOptions;
import net.minecraft.world.gen.WorldPresets;
import net.minecraft.world.level.LevelInfo;

/**
 * Сценарий демонстрационного ролика. Включается только флагом -Dolegus.demo=true
 * (./gradlew runDemo) и в jar мода не попадает. Создаёт плоский мир, расставляет мобов
 * и играет за игрока: целится, выпускает стрелу, жмёт клавиши режима и свиста — теми же
 * путями, что и живой игрок. Вверху экрана идут подписи.
 */
public class OlegusArrowDemo implements ClientModInitializer {
    private static final boolean ENABLED = Boolean.getBoolean("olegus.demo");
    /** Время начала и конца сценария — по нему обрезается запись экрана. */
    private static final Path MARKERS = Path.of("demo-markers.txt");
    private static final int WARMUP_TICKS = 80;
    /** Игрок висит над полем, камера — от третьего лица со спины. */
    private static final Vec3d HOVER = new Vec3d(0.5, -54.0, -3.5);
    private static final float VIEW_PITCH = 30.0F;

    private record Step(int tick, Consumer<MinecraftClient> action) {
    }

    private final List<Step> script = new ArrayList<>();
    private boolean worldRequested;
    private int warmup;
    private int tick = -1;
    private Text caption = Text.empty();
    private float lookYaw;
    private float lookPitch = VIEW_PITCH;
    private boolean steering;

    @Override
    public void onInitializeClient() {
        if (!ENABLED) {
            return;
        }
        this.writeScript();
        ClientTickEvents.END_CLIENT_TICK.register(this::onTick);
        HudRenderCallback.EVENT.register(this::renderCaption);
    }

    // ---------------------------------------------------------------- сценарий

    private void writeScript() {
        at(0.0, client -> {
            mark("start");
            command(client,
                    "gamerule sendCommandFeedback false",
                    "gamerule commandBlockOutput false",
                    "gamerule doDaylightCycle false",
                    "gamerule doWeatherCycle false",
                    "gamerule doMobSpawning false",
                    "time set 6000",
                    "weather clear",
                    "difficulty easy",
                    "gamemode survival @a",
                    "give @a olegus_arrow:olegus_arrow",
                    "tp @a " + HOVER.x + " " + HOVER.y + " " + HOVER.z + " 0 " + VIEW_PITCH,
                    mob("husk", -4, 8, "Цель"),
                    mob("husk", 4, 9, null),
                    mob("pig", 1, 13, null),
                    mob("sheep", -2, 15, null));
            server(client, server -> {
                // Выживание — чтобы были видны сердечки, но игрок висит в воздухе и неуязвим.
                ServerPlayerEntity player = server.getPlayerManager().getPlayerList().get(0);
                player.getAbilities().allowFlying = true;
                player.getAbilities().flying = true;
                player.getAbilities().invulnerable = true;
                player.sendAbilitiesUpdate();
            });
            client.options.setPerspective(Perspective.THIRD_PERSON_BACK);
            this.look(0.0F, VIEW_PITCH);
            this.caption = Text.literal("Стрела Олегуса — демонстрация мода");
        });
        at(3.0, client -> this.caption = Text.literal("Режим «Выбранная цель»: стрела бьёт только моба под прицелом"));
        at(3.5, client -> this.lookAt(client, new Vec3d(-3.5, -59.0, 8.5)));
        at(5.0, this::useArrow);
        at(10.0, client -> {
            this.caption = Text.literal("Цель повержена. Остальных стрела не тронула и ждёт рядом");
            this.look(0.0F, VIEW_PITCH);
        });
        at(13.0, client -> {
            this.caption = Text.literal("Клавиша R — режим «Все мобы в радиусе 50 блоков»");
            command(client, "fill 5 -60 18 15 -56 18 minecraft:stone_bricks");
            for (int i = 0; i < 6; i++) {
                command(client, mob("husk", 8 + (i % 3) * 2, 22 + (i / 3) * 2, null));
            }
        });
        at(14.0, client -> press(ArrowKeyBindings.TOGGLE_MODE));
        at(17.0, client -> this.caption = Text.literal("Стрела по очереди атакует всех мобов и облетает стены"));
        at(23.0, client -> this.caption = Text.literal("В толпе больше трёх мобов она разгоняется до ×2, урон прежний"));
        at(31.0, client -> this.caption = Text.literal("Свист (клавиша G): стрела отзывается и возвращается в руку"));
        at(32.0, client -> press(ArrowKeyBindings.WHISTLE));
        at(36.0, client -> {
            this.caption = Text.literal("Снова в бой: стрела действует 40 секунд");
            spawnWave(client, 0);
        });
        at(37.0, this::useArrow);
        at(47.0, client -> {
            this.caption = Text.literal("Над полоской здоровья — режим, оставшееся время и победы");
            spawnWave(client, 1);
        });
        at(57.0, client -> {
            this.caption = Text.literal("Игроков стрела не трогает — только мобов");
            spawnWave(client, 2);
        });
        at(72.0, client -> this.caption = Text.literal("За 5 секунд до конца звенит колокольчик"));
        at(77.0, client -> this.caption = Text.literal("Время вышло: стрела кружит вокруг хозяина…"));
        at(80.5, client -> this.caption = Text.literal("…и падает предметом, который можно подобрать"));
        at(82.5, client -> server(client, server -> {
            // Игрок спускается к упавшей стреле и подбирает её.
            ServerPlayerEntity player = server.getPlayerManager().getPlayerList().get(0);
            List<ItemEntity> drops = player.getServerWorld().getEntitiesByClass(ItemEntity.class,
                    player.getBoundingBox().expand(24.0), item -> item.getStack().isOf(ModItems.OLEGUS_ARROW));
            if (!drops.isEmpty()) {
                ItemEntity drop = drops.get(0);
                player.getAbilities().flying = false;
                player.sendAbilitiesUpdate();
                player.requestTeleport(drop.getX(), drop.getY(), drop.getZ() - 0.8);
            }
        }));
        at(86.0, client -> this.caption = Text.literal("Стрела снова в инвентаре. Конец демонстрации"));
        at(90.0, client -> {
            mark("end");
            client.scheduleStop();
        });
    }

    private void at(double seconds, Consumer<MinecraftClient> action) {
        this.script.add(new Step((int) Math.round(seconds * 20.0), action));
    }

    /** Волны мобов, чтобы стрела была занята все 40 секунд. */
    private static void spawnWave(MinecraftClient client, int wave) {
        int[][] spots = {
                {-10, 10}, {12, 6}, {-6, 20}, {6, 28}, {0, 34},
                {-14, 24}, {16, 16}, {-3, 26}, {9, 3}, {-12, 4},
                {14, 30}, {-8, 32}, {3, 20}, {-16, 14}, {18, 22},
        };
        for (int i = wave * 5; i < wave * 5 + 5; i++) {
            command(client, mob(i % 2 == 0 ? "husk" : "pig", spots[i][0], spots[i][1], null));
        }
    }

    private static String mob(String type, int x, int z, String name) {
        String tags = "NoAI:1b,PersistenceRequired:1b";
        if (name != null) {
            tags += ",CustomName:'\"" + name + "\"',CustomNameVisible:1b";
        }
        return "summon minecraft:" + type + " " + (x + 0.5) + " -60 " + (z + 0.5) + " {" + tags + "}";
    }

    /** ПКМ с предметом в руке — тот же путь, что у игрока. */
    private void useArrow(MinecraftClient client) {
        if (client.interactionManager != null && client.player != null) {
            client.interactionManager.interactItem(client.player, Hand.MAIN_HAND);
            client.player.swingHand(Hand.MAIN_HAND);
        }
    }

    /** Нажатие клавиши: мод обработает его в своём обработчике тика, как настоящее. */
    private static void press(KeyBinding binding) {
        KeyBinding.onKeyPressed(KeyBindingHelper.getBoundKeyOf(binding));
    }

    // ---------------------------------------------------------------- тик

    private void onTick(MinecraftClient client) {
        if (!this.worldRequested && client.currentScreen instanceof TitleScreen) {
            this.worldRequested = true;
            createWorld(client);
            return;
        }
        if (client.world == null || client.player == null || client.getServer() == null) {
            return;
        }
        if (this.warmup < WARMUP_TICKS) {
            this.warmup++;
            return;
        }
        this.tick++;
        for (Step step : this.script) {
            if (step.tick() == this.tick) {
                step.action().accept(client);
            }
        }
        this.steer(client.player);
    }

    private static void createWorld(MinecraftClient client) {
        LevelInfo info = new LevelInfo("olegus-demo", GameMode.CREATIVE, false, Difficulty.EASY, true,
                new GameRules(), DataConfiguration.SAFE_MODE);
        client.createIntegratedServerLoader().createAndStart("olegus-demo", info,
                new GeneratorOptions(20261024L, false, false),
                registries -> registries.getWrapperOrThrow(RegistryKeys.WORLD_PRESET)
                        .getOrThrow(WorldPresets.FLAT).value().createDimensionsRegistryHolder(),
                client.currentScreen);
    }

    // ---------------------------------------------------------------- камера

    private void look(float yaw, float pitch) {
        this.lookYaw = yaw;
        this.lookPitch = pitch;
        this.steering = true;
    }

    private void lookAt(MinecraftClient client, Vec3d target) {
        Vec3d eye = client.player.getEyePos();
        double dx = target.x - eye.x;
        double dy = target.y - eye.y;
        double dz = target.z - eye.z;
        float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
        this.look(yaw, pitch);
    }

    /** Плавный поворот головы — чтобы в ролике не было рывков. */
    private void steer(ClientPlayerEntity player) {
        if (!this.steering) {
            return;
        }
        float yaw = player.getYaw() + MathHelper.wrapDegrees(this.lookYaw - player.getYaw()) * 0.15F;
        float pitch = player.getPitch() + (this.lookPitch - player.getPitch()) * 0.15F;
        player.setYaw(yaw);
        player.setPitch(pitch);
        player.setHeadYaw(yaw);
        player.setBodyYaw(yaw);
    }

    // ---------------------------------------------------------------- подписи

    private void renderCaption(DrawContext context, RenderTickCounter tickCounter) {
        if (this.tick < 0 || this.caption.getString().isEmpty()) {
            return;
        }
        MinecraftClient client = MinecraftClient.getInstance();
        TextRenderer font = client.textRenderer;
        float scale = 1.25F;
        int width = Math.round(font.getWidth(this.caption) * scale);
        int centerX = context.getScaledWindowWidth() / 2;
        context.fill(centerX - width / 2 - 8, 6, centerX + width / 2 + 8, 6 + Math.round(9 * scale) + 8, 0xA0101018);
        context.getMatrices().push();
        context.getMatrices().translate(centerX, 10.0F, 0.0F);
        context.getMatrices().scale(scale, scale, 1.0F);
        context.drawCenteredTextWithShadow(font, this.caption, 0, 0, 0xFFFFFF);
        context.getMatrices().pop();
    }

    // ---------------------------------------------------------------- служебное

    private static void server(MinecraftClient client, Consumer<MinecraftServer> action) {
        MinecraftServer server = client.getServer();
        if (server != null) {
            server.execute(() -> action.accept(server));
        }
    }

    private static void command(MinecraftClient client, String... commands) {
        server(client, server -> {
            for (String command : commands) {
                server.getCommandManager().executeWithPrefix(server.getCommandSource(), command);
            }
        });
    }

    private static void mark(String name) {
        try {
            Files.writeString(MARKERS, name + " " + System.currentTimeMillis() + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            OlegusArrowMod.LOGGER.warn("Не удалось записать отметку {}", name, e);
        }
    }
}
