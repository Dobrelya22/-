package com.olegus.arrow.demo;

import com.olegus.arrow.OlegusArrowMod;
import com.olegus.arrow.client.ArrowKeyBindings;
import com.olegus.arrow.client.ClientArrowState;
import com.olegus.arrow.entity.OlegusArrowEntity;
import com.olegus.arrow.registry.ModItems;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.client.sound.Sound;
import net.minecraft.client.sound.SoundInstance;
import net.minecraft.client.sound.TickableSoundInstance;
import net.minecraft.client.sound.WeightedSoundSet;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.resource.DataConfiguration;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.sound.SoundCategory;
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
 *
 * <p>Программный OpenGL в CI рисует всего несколько кадров в секунду, поэтому игру можно
 * замедлить (переменная OLEGUS_DEMO_TICK_RATE → /tick rate) и потом ускорить запись до
 * плавных 60 кадров. Звук при этом пишется не с колонок, а журналом: какой звук, когда,
 * где и с какой громкостью (demo-sounds.txt) — из него tools/demo/sound_track.py собирает
 * звуковую дорожку в масштабе ролика.
 */
public class OlegusArrowDemo implements ClientModInitializer {
    private static final boolean ENABLED = Boolean.getBoolean("olegus.demo");
    /** Время начала и конца сценария — по нему обрезается запись экрана. */
    private static final Path MARKERS = Path.of("demo-markers.txt");
    private static final Path SOUNDS = Path.of("demo-sounds.txt");
    private static final int WARMUP_TICKS = 80;
    /** На каком тике разминки замедлять игру: мир уже загружен, но сценарий ещё не начался. */
    private static final int SLOWDOWN_TICK = 40;
    private static final float TICK_RATE = tickRateFromEnvironment();
    /** Игрок висит над полем, камера — от третьего лица со спины. */
    private static final Vec3d HOVER = new Vec3d(0.5, -56.5, -2.5);
    private static final float VIEW_PITCH = 28.0F;
    /** Плавность поворота камеры: доля оставшегося угла за тик. */
    private static final float STEER_SMOOTHING = 0.12F;
    /**
     * Камера от третьего лица смотрит «сквозь» голову игрока, и то, за чем следим, оказалось бы
     * прямо за его фигурой. Смещаем взгляд, чтобы бой был виден правее и выше игрока.
     */
    private static final float FOLLOW_YAW_OFFSET = -14.0F;
    private static final float FOLLOW_PITCH_OFFSET = 7.0F;

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
    /** Пока стрела охотится, камера следит за ней и её целью. */
    private boolean follow;
    private PrintWriter soundLog;
    /** После конца сценария журнал не трогаем: повторное открытие стёрло бы его. */
    private boolean soundLogClosed;
    /** Звуки, которые меняют громкость и высоту на ходу (свист стрелы в полёте), и их номера в журнале. */
    private final Map<TickableSoundInstance, Integer> loops = new IdentityHashMap<>();
    private int loopCount;

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
                    "gamerule announceAdvancements false",
                    "kill @e[type=!minecraft:player]",
                    "time set 6000",
                    "weather clear",
                    "difficulty easy",
                    "gamemode survival @a",
                    "give @a olegus_arrow:olegus_arrow",
                    "tp @a " + HOVER.x + " " + HOVER.y + " " + HOVER.z + " 0 " + VIEW_PITCH,
                    mob("husk", -3, 5, "Цель"),
                    mob("husk", 3, 6, null),
                    mob("pig", 0, 9, null),
                    mob("sheep", -1, 11, null));
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
        // Животные, сгенерированные вместе с миром, убраны выше; убираем и выпавший с них лут.
        at(0.2, client -> command(client, "kill @e[type=minecraft:item]", "kill @e[type=minecraft:experience_orb]"));
        // Секунды совпадают с закадровым голосом: tools/demo/narration.txt.
        at(9.5, client -> this.caption = Text.literal("Режим «Выбранная цель»: стрела бьёт только моба под прицелом"));
        at(10.0, client -> this.lookAt(client, new Vec3d(-2.5, -59.0, 5.5)));
        at(15.0, client -> {
            this.useArrow(client);
            this.follow = true;
        });
        at(22.4, client -> {
            this.caption = Text.literal("Цель повержена. Остальных стрела не тронула и ждёт рядом");
            this.look(0.0F, VIEW_PITCH);
        });
        at(27.2, client -> this.caption = Text.literal("Свист (клавиша G): стрела отзывается и возвращается в руку"));
        at(30.4, client -> press(ArrowKeyBindings.WHISTLE));
        at(36.4, client -> {
            this.caption = Text.literal("Клавиша R — режим «Все мобы в радиусе 50 блоков»");
            command(client, "fill 2 -60 10 9 -57 10 minecraft:stone_bricks");
            for (int i = 0; i < 6; i++) {
                command(client, mob("husk", 3 + (i % 3) * 2, 13 + (i / 3) * 2, null));
            }
        });
        at(37.6, client -> press(ArrowKeyBindings.TOGGLE_MODE));
        // Второй пуск: дальше все 40 секунд жизни стрелы, до колокольчика и финала.
        at(43.8, this::useArrow);
        at(45.4, client -> this.caption = Text.literal("Стрела по очереди атакует всех мобов и облетает стены"));
        at(50.0, client -> spawnWave(client, 0));
        at(51.0, client -> this.caption = Text.literal("В толпе больше трёх мобов она разгоняется до ×2, урон прежний"));
        at(59.0, client -> {
            this.caption = Text.literal("Над полоской здоровья — режим, оставшееся время и победы");
            spawnWave(client, 1);
        });
        at(68.0, client -> spawnWave(client, 2));
        at(72.6, client -> this.caption = Text.literal("Игроков стрела не трогает — только мобов"));
        at(78.8, client -> this.caption = Text.literal("За 5 секунд до конца звенит колокольчик"));
        at(83.8, client -> this.caption = Text.literal("Время вышло: стрела кружит вокруг хозяина…"));
        at(87.3, client -> this.caption = Text.literal("…и падает предметом, который можно подобрать"));
        at(89.3, client -> server(client, server -> {
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
        at(91.8, client -> this.caption = Text.literal("Стрела снова в инвентаре. Конец демонстрации"));
        at(99.5, client -> {
            mark("end");
            this.closeSoundLog();
            client.scheduleStop();
        });
    }

    private void at(double seconds, Consumer<MinecraftClient> action) {
        this.script.add(new Step((int) Math.round(seconds * 20.0), action));
    }

    /** Волны мобов, чтобы стрела была занята все 40 секунд. */
    private static void spawnWave(MinecraftClient client, int wave) {
        int[][] spots = {
                {-7, 6}, {8, 4}, {-4, 12}, {6, 14}, {0, 17},
                {-9, 13}, {10, 9}, {-2, 15}, {5, 2}, {-8, 3},
                {9, 15}, {-6, 17}, {2, 12}, {-10, 8}, {11, 12},
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
            if (this.warmup == 0) {
                client.getSoundManager().registerListener(this::onSoundPlayed);
            }
            if (this.warmup == SLOWDOWN_TICK && TICK_RATE != 20.0F) {
                command(client, String.format(Locale.ROOT, "tick rate %.4f", TICK_RATE));
            }
            this.warmup++;
            return;
        }
        this.tick++;
        for (Step step : this.script) {
            if (step.tick() == this.tick) {
                step.action().accept(client);
            }
        }
        // Всплывающие «Новое достижение» загораживают кадр.
        client.getToastManager().clear();
        this.steer(client);
        this.logLoopsAndCamera(client);
        if (this.tick % 40 == 0) {
            OlegusArrowMod.LOGGER.info("Демо: секунда {}, {} кадров/с", this.tick / 20, client.getCurrentFps());
        }
    }

    private static float tickRateFromEnvironment() {
        String value = System.getenv("OLEGUS_DEMO_TICK_RATE");
        try {
            return value == null || value.isBlank() ? 20.0F : MathHelper.clamp(Float.parseFloat(value), 1.0F, 20.0F);
        } catch (NumberFormatException e) {
            return 20.0F;
        }
    }

    private static void createWorld(MinecraftClient client) {
        // Правила сразу при создании: иначе за время загрузки успевают появиться слизни,
        // и стрела в режиме «все мобы» улетает за ними к горизонту.
        GameRules rules = new GameRules();
        rules.get(GameRules.DO_MOB_SPAWNING).set(false, null);
        rules.get(GameRules.DO_DAYLIGHT_CYCLE).set(false, null);
        rules.get(GameRules.DO_WEATHER_CYCLE).set(false, null);
        rules.get(GameRules.ANNOUNCE_ADVANCEMENTS).set(false, null);
        LevelInfo info = new LevelInfo("olegus-demo", GameMode.CREATIVE, false, Difficulty.EASY, true,
                rules, DataConfiguration.SAFE_MODE);
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

    /**
     * Плавный поворот головы — чтобы в ролике не было рывков. Пока стрела охотится, камера
     * смотрит на середину между ней и её целью, иначе — на общий план поля.
     */
    private void steer(MinecraftClient client) {
        ClientPlayerEntity player = client.player;
        if (!this.steering) {
            return;
        }
        float wantYaw = this.lookYaw;
        float wantPitch = this.lookPitch;
        OlegusArrowEntity arrow = ClientArrowState.activeArrow();
        if (this.follow && arrow != null && arrow.getStatus() == OlegusArrowEntity.STATUS_HUNTING) {
            Vec3d focus = arrow.getPos();
            Entity target = client.world.getEntityById(arrow.getTargetId());
            if (target != null) {
                focus = focus.add(target.getPos()).multiply(0.5);
            }
            Vec3d eye = player.getEyePos();
            double dx = focus.x - eye.x;
            double dy = focus.y - eye.y;
            double dz = focus.z - eye.z;
            wantYaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0) + FOLLOW_YAW_OFFSET;
            wantPitch = MathHelper.clamp(
                    (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz))) + FOLLOW_PITCH_OFFSET,
                    12.0F, 55.0F);
        }
        float yaw = player.getYaw() + MathHelper.wrapDegrees(wantYaw - player.getYaw()) * STEER_SMOOTHING;
        float pitch = player.getPitch() + (wantPitch - player.getPitch()) * STEER_SMOOTHING;
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

    // ---------------------------------------------------------------- журнал звуков

    /**
     * Каждый прозвучавший звук: время (мс), файл, громкость с учётом настроек, высота, дальность
     * слышимости (inf — без затухания), координаты и номер, если звук меняется на ходу.
     */
    private void onSoundPlayed(SoundInstance sound, WeightedSoundSet soundSet, float range) {
        Sound chosen = sound.getSound();
        if (this.tick < 0 || chosen == null) {
            return;
        }
        int loop = 0;
        if (sound instanceof TickableSoundInstance tickable && !tickable.isDone()) {
            loop = ++this.loopCount;
            this.loops.put(tickable, loop);
        }
        this.log(String.format(Locale.ROOT, "sound %d %s %.4f %.4f %s %.3f %.3f %.3f %d %s",
                System.currentTimeMillis(), chosen.getLocation(), gain(sound), pitch(sound),
                Float.isInfinite(range) ? "inf" : String.format(Locale.ROOT, "%.2f", range),
                sound.getX(), sound.getY(), sound.getZ(), loop, sound.isRelative() ? "rel" : "abs"));
    }

    /** Громкость и высота меняющихся звуков и положение слушателя — каждый тик. */
    private void logLoopsAndCamera(MinecraftClient client) {
        long now = System.currentTimeMillis();
        for (Iterator<Map.Entry<TickableSoundInstance, Integer>> it = this.loops.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<TickableSoundInstance, Integer> entry = it.next();
            TickableSoundInstance sound = entry.getKey();
            if (sound.isDone() || !client.getSoundManager().isPlaying(sound)) {
                this.log("stop " + now + " " + entry.getValue());
                it.remove();
            } else {
                this.log(String.format(Locale.ROOT, "loop %d %d %.4f %.4f %.3f %.3f %.3f", now, entry.getValue(),
                        gain(sound), pitch(sound), sound.getX(), sound.getY(), sound.getZ()));
            }
        }
        Camera camera = client.gameRenderer.getCamera();
        this.log(String.format(Locale.ROOT, "camera %d %.3f %.3f %.3f %.2f", now,
                camera.getPos().x, camera.getPos().y, camera.getPos().z, camera.getYaw()));
    }

    /** Как SoundSystem: громкость звука × громкость категории, не больше 1, и общая громкость. */
    private static float gain(SoundInstance sound) {
        MinecraftClient client = MinecraftClient.getInstance();
        SoundCategory category = sound.getCategory();
        float categoryVolume = category == null || category == SoundCategory.MASTER
                ? 1.0F : client.options.getSoundVolume(category);
        return MathHelper.clamp(sound.getVolume() * categoryVolume, 0.0F, 1.0F)
                * client.options.getSoundVolume(SoundCategory.MASTER);
    }

    private static float pitch(SoundInstance sound) {
        return MathHelper.clamp(sound.getPitch(), 0.5F, 2.0F);
    }

    private void log(String line) {
        if (this.soundLogClosed) {
            return;
        }
        try {
            if (this.soundLog == null) {
                this.soundLog = new PrintWriter(Files.newBufferedWriter(SOUNDS, StandardCharsets.UTF_8));
            }
            this.soundLog.println(line);
        } catch (IOException e) {
            OlegusArrowMod.LOGGER.warn("Не удалось записать журнал звуков", e);
        }
    }

    private void closeSoundLog() {
        this.soundLogClosed = true;
        if (this.soundLog != null) {
            this.soundLog.close();
        }
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
