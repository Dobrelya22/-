package com.andryushka.arrow.demo;

import com.andryushka.arrow.AndryushkaArrowMod;
import com.andryushka.arrow.ArrowTrail;
import com.andryushka.arrow.client.ArrowKeyBindings;
import com.andryushka.arrow.client.ClientArrowState;
import com.andryushka.arrow.entity.AndryushkaArrowEntity;
import com.andryushka.arrow.registry.ModComponents;
import com.andryushka.arrow.registry.ModItems;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
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
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.advancement.AdvancementEntry;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ChatScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.screen.advancement.AdvancementsScreen;
import net.minecraft.client.gui.screen.ingame.CraftingScreen;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.client.network.ClientAdvancementManager;
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
import net.minecraft.entity.EntityType;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.mob.HuskEntity;
import net.minecraft.entity.passive.WolfEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.s2c.play.UpdateSelectedSlotS2CPacket;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.resource.DataConfiguration;
import net.minecraft.screen.CraftingScreenHandler;
import net.minecraft.screen.ScreenHandlerContext;
import net.minecraft.screen.SimpleNamedScreenHandlerFactory;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.Difficulty;
import net.minecraft.world.GameMode;
import net.minecraft.world.GameRules;
import net.minecraft.world.gen.GeneratorOptions;
import net.minecraft.world.gen.WorldPresets;
import net.minecraft.world.level.LevelInfo;

/**
 * Сценарий обзорного ролика (примерно 10 минут). Включается только флагом -Dandryushka.demo=true
 * (./gradlew runDemo) и в jar мода не попадает. Создаёт плоский мир и играет за игрока: крафтит,
 * целится, выпускает стрелу, жмёт клавиши режима и свиста, бьёт мобов — теми же путями, что
 * и живой игрок. Вверху экрана идут заголовки сцен.
 *
 * <p>Действия привязаны к фразам закадрового голоса: tools/demo/narration.py пишет файл меток
 * («метка начало конец», путь — в ANDRYUSHKA_DEMO_CUES), и если фраза сдвинулась, сдвигаются и её
 * действия. Без файла меток берутся секунды прямо из tools/demo/narration.txt.
 *
 * <p>Программный OpenGL в CI рисует медленно, поэтому игру можно замедлить (ANDRYUSHKA_DEMO_TICK_RATE
 * → /tick rate) и потом ускорить запись до плавных 60 кадров. Звук при этом пишется не с колонок,
 * а журналом (demo-sounds.txt) — из него tools/demo/sound_track.py собирает дорожку в масштабе ролика.
 */
public class AndryushkaArrowDemo implements ClientModInitializer {
    private static final boolean ENABLED = Boolean.getBoolean("andryushka.demo");
    /** Время начала и конца сценария — по нему обрезается запись экрана. */
    private static final Path MARKERS = Path.of("demo-markers.txt");
    private static final Path SOUNDS = Path.of("demo-sounds.txt");
    private static final Path DEFAULT_NARRATION = Path.of("../tools/demo/narration.txt");
    /**
     * Какой ролик снимаем: review — обзор под закадровый голос, update — дубли для апдейт-ролика
     * (tools/demo/update-takes.txt), которые потом монтирует tools/demo/update/montage.py.
     */
    private static final String SCENARIO = System.getenv().getOrDefault("ANDRYUSHKA_DEMO_SCENARIO", "review");
    private static final boolean UPDATE_VIDEO = SCENARIO.equals("update");
    private static final Path UPDATE_TAKES = Path.of("../tools/demo/update-takes.txt");
    private static final int WARMUP_TICKS = 80;
    /** На каком тике разминки замедлять игру: мир уже загружен, но сценарий ещё не начался. */
    private static final int SLOWDOWN_TICK = 40;
    private static final float TICK_RATE = tickRateFromEnvironment();
    /** Игрок висит над полем, камера — от третьего лица со спины. */
    private static final Vec3d HOVER = new Vec3d(0.5, -56.5, -2.5);
    private static final int GROUND = -60;
    private static final float VIEW_PITCH = 28.0F;
    /** Верстак для сцен крафта — прямо под игроком. */
    private static final BlockPos TABLE = new BlockPos(0, GROUND, -1);
    /** Плавность поворота камеры: доля оставшегося угла за тик. */
    private static final float STEER_SMOOTHING = 0.12F;
    /**
     * Камера от третьего лица смотрит «сквозь» голову игрока, и то, за чем следим, оказалось бы
     * прямо за его фигурой. Смещаем взгляд, чтобы бой был виден правее и выше игрока.
     */
    private static final float FOLLOW_YAW_OFFSET = -14.0F;
    private static final float FOLLOW_PITCH_OFFSET = 7.0F;
    private static final String[] TRAILS = {"fire", "ice", "toxic", "void", "gold", "rainbow"};

    private record Step(int tick, Consumer<MinecraftClient> action) {
    }

    /** Что показывать подсказкой поверх игры. */
    private enum Tooltip { NONE, CRAFT_RESULT, ARROW }

    private final List<Step> script = new ArrayList<>();
    private final Map<String, Double> cues = new HashMap<>();
    private boolean worldRequested;
    private int warmup;
    private int tick = -1;
    private int endTick = Integer.MAX_VALUE;
    private Text caption = Text.empty();
    private float lookYaw;
    private float lookPitch = VIEW_PITCH;
    private boolean steering;
    /** Пока стрела охотится, камера следит за ней и её целью. */
    private boolean follow;
    /** Медленный поворот камеры: от какого угла, к какому и за сколько тиков. */
    private float panFrom;
    private float panTo;
    private int panStart = -1;
    private int panTicks;
    /** Куда смотрит камера на прошлом и на этом тике; между ними угол считается в каждом кадре. */
    private float cameraYaw;
    private float cameraPitch;
    private float prevCameraYaw;
    private float prevCameraPitch;
    private boolean cameraReady;
    private Tooltip tooltip = Tooltip.NONE;
    private PrintWriter soundLog;
    /** После конца сценария журнал не трогаем: повторное открытие стёрло бы его. */
    private boolean soundLogClosed;
    /** Звуки, которые меняют громкость и высоту на ходу (бас стрелы в полёте), и их номера в журнале. */
    private final Map<TickableSoundInstance, Integer> loops = new IdentityHashMap<>();
    private int loopCount;

    @Override
    public void onInitializeClient() {
        if (!ENABLED) {
            return;
        }
        this.loadCues();
        this.writeScript();
        ClientTickEvents.END_CLIENT_TICK.register(this::onTick);
        HudRenderCallback.EVENT.register(this::renderOverlay);
        WorldRenderEvents.START.register(context -> this.applyCamera());
        ScreenEvents.AFTER_INIT.register((client, screen, width, height) ->
                ScreenEvents.afterRender(screen).register(this::renderScreenOverlay));
    }

    // ---------------------------------------------------------------- метки

    private void loadCues() {
        String timing = System.getenv("ANDRYUSHKA_DEMO_CUES");
        try {
            if (timing != null && Files.exists(Path.of(timing))) {
                for (String line : Files.readAllLines(Path.of(timing), StandardCharsets.UTF_8)) {
                    String[] parts = line.trim().split("\\s+");
                    if (parts.length >= 2) {
                        this.cues.put(parts[0], Double.parseDouble(parts[1]));
                    }
                }
            } else {
                for (String line : Files.readAllLines(UPDATE_VIDEO ? UPDATE_TAKES : DEFAULT_NARRATION, StandardCharsets.UTF_8)) {
                    String[] parts = line.trim().split("\\s+", 3);
                    if (parts.length >= 2 && !line.startsWith("#")) {
                        this.cues.put(parts[1], Double.parseDouble(parts[0]));
                    }
                }
            }
        } catch (IOException | NumberFormatException e) {
            AndryushkaArrowMod.LOGGER.error("Демо: не прочитать метки озвучки", e);
        }
        AndryushkaArrowMod.LOGGER.info("Демо: меток {}, конец на {} с", this.cues.size(), this.cues.get("end"));
    }

    /** Действие через {@code offset} секунд после начала фразы {@code cue}. */
    private void on(String cue, double offset, Consumer<MinecraftClient> action) {
        Double start = this.cues.get(cue);
        if (start == null) {
            AndryushkaArrowMod.LOGGER.warn("Демо: нет метки {}", cue);
            return;
        }
        this.script.add(new Step((int) Math.round(Math.max(0.0, start + offset) * 20.0), action));
    }

    // ---------------------------------------------------------------- сценарий

    private void writeScript() {
        if (UPDATE_VIDEO) {
            this.updateTakes();
        } else {
            this.review();
        }
        Double end = this.cues.get("end");
        this.endTick = end != null ? (int) Math.round(end * 20.0) : 600 * 20;
        this.script.add(new Step(this.endTick, client -> {
            mark("end");
            this.closeSoundLog();
            client.scheduleStop();
        }));
    }

    private void review() {
        this.intro();
        this.crafting();
        this.targetMode();
        this.allMode();
        this.friends();
        this.loot();
        this.guard();
        this.assist();
        this.levels();
        this.combo();
        this.grinder();
        this.trails();
        this.advancements();
        this.finale();
    }

    // ---------------------------------------------------------------- апдейт-ролик

    /**
     * Дубли для апдейт-ролика «как было — как стало»: без заголовков сцен, надписи добавит монтаж.
     * Каждый дубль звучит дважды — со звуками 1.3.0 и, через tools/demo/sound_track.py --before,
     * со звуками 1.2.1, поэтому сравнение идёт на одних и тех же кадрах.
     */
    private void updateTakes() {
        on("setup", -0.5, client -> {
            mark("start");
            command(client,
                    "gamerule doDaylightCycle false",
                    "gamerule doWeatherCycle false",
                    "gamerule doMobSpawning false",
                    "tp @e[type=!minecraft:player] 0 -200 0",
                    "time set 6000",
                    "weather clear",
                    "difficulty easy",
                    "gamemode survival @a",
                    "tp @a " + HOVER.x + " " + HOVER.y + " " + HOVER.z + " 0 " + VIEW_PITCH);
            giveArrow(client, 0, "rainbow");
            server(client, server -> {
                ServerPlayerEntity player = player(server);
                player.getAbilities().allowFlying = true;
                player.getAbilities().flying = true;
                player.getAbilities().invulnerable = true;
                player.sendAbilitiesUpdate();
            });
            client.options.setPerspective(Perspective.THIRD_PERSON_BACK);
            client.inGameHud.getChatHud().clear(false);
            this.snapCamera(0.0F, VIEW_PITCH);
        });
        on("setup", 0.0, client -> command(client, "kill @e[type=minecraft:item]", "kill @e[type=minecraft:experience_orb]"));
        // «Выбранная цель» → «Все мобы» → «Охрана».
        on("setup", 1.0, client -> press(ArrowKeyBindings.TOGGLE_MODE));
        on("setup", 1.6, client -> press(ArrowKeyBindings.TOGGLE_MODE));

        // Полёт: в «Охране» без врагов стрела просто кружит рядом — слышно только её.
        on("flight", -0.6, AndryushkaArrowDemo::selectArrow);
        on("flight", 0.0, client -> {
            this.useArrow(client);
            this.pan(-25.0F, 25.0F, 14.0);
        });
        on("flight", 13.5, client -> press(ArrowKeyBindings.WHISTLE));

        // Удары: хаск-«танк» на 72 здоровья и урон 4 — восемнадцать ударов подряд, без серии. Стоит
        // сбоку: прямо по курсу его закрыла бы фигура игрока. Камера на месте — видно и игрока, и танк.
        on("hits", -0.3, client -> {
            clearStage(client);
            command(client, "gamerule andryushkaArrowDamage 4", summon("husk", -6, 8,
                    "NoAI:1b,PersistenceRequired:1b,Health:72f,CustomName:'\"Танк\"',CustomNameVisible:1b,"
                            + "Rotation:[150.0f,0.0f],attributes:[{id:\"minecraft:generic.max_health\",base:72.0d}]"));
            giveArrow(client, 0, "rainbow");
            this.follow = false;
            this.look(0.0F, VIEW_PITCH);
        });
        on("hits", 0.5, client -> press(ArrowKeyBindings.TOGGLE_MODE));
        // Навестись на танк, выпустить стрелу и вернуть камеру.
        on("hits", 1.0, client -> this.lookAt(client, new Vec3d(-5.5, GROUND + 1.0, 8.5)));
        on("hits", 2.2, AndryushkaArrowDemo::selectArrow);
        on("hits", 3.0, this::useArrow);
        on("hits", 3.4, client -> this.look(10.0F, VIEW_PITCH));
        on("hits", 15.5, client -> press(ArrowKeyBindings.WHISTLE));

        // Серия: двадцать хасков по кругу и стрела V уровня — победы одна за другой.
        on("streak", -0.3, client -> {
            clearStage(client);
            command(client, "gamerule andryushkaArrowDamage 100");
            giveArrow(client, 150, "rainbow");
            this.follow = false;
            this.look(0.0F, VIEW_PITCH);
            for (int i = 0; i < 20; i++) {
                double angle = 2 * Math.PI * i / 20;
                command(client, mob("husk", (int) Math.round(Math.cos(angle) * 9),
                        9 + (int) Math.round(Math.sin(angle) * 8), ""));
            }
        });
        on("streak", 0.5, client -> press(ArrowKeyBindings.TOGGLE_MODE));
        on("streak", 1.5, AndryushkaArrowDemo::selectArrow);
        on("streak", 2.2, client -> {
            this.useArrow(client);
            this.follow = true;
        });
        on("streak", 20.0, client -> press(ArrowKeyBindings.WHISTLE));

        // Ещё одна мясорубка — с огненным следом, для заставки.
        on("fire", -0.3, client -> {
            clearStage(client);
            giveArrow(client, 150, "fire");
            this.follow = false;
            this.look(0.0F, VIEW_PITCH);
            for (int i = 0; i < 16; i++) {
                double angle = 2 * Math.PI * i / 8 + (i >= 8 ? Math.PI / 8 : 0.0);
                double radius = i >= 8 ? 11.0 : 6.0;
                command(client, mob("husk", (int) Math.round(Math.cos(angle) * radius),
                        10 + (int) Math.round(Math.sin(angle) * radius * 0.8), ""));
            }
        });
        on("fire", 1.0, AndryushkaArrowDemo::selectArrow);
        on("fire", 1.8, client -> {
            this.useArrow(client);
            this.follow = true;
        });
        on("fire", 19.0, client -> press(ArrowKeyBindings.WHISTLE));

        // Золотой след кружит вокруг игрока под панораму — для концовки.
        on("orbit", -0.3, client -> {
            clearStage(client);
            giveArrow(client, 150, "gold");
            this.follow = false;
            this.look(-30.0F, 14.0F);
        });
        on("orbit", 0.5, client -> press(ArrowKeyBindings.TOGGLE_MODE));
        on("orbit", 1.0, AndryushkaArrowDemo::selectArrow);
        on("orbit", 1.6, client -> {
            this.useArrow(client);
            this.pan(-30.0F, 30.0F, 15.0);
        });
        on("orbit", 16.0, client -> press(ArrowKeyBindings.WHISTLE));
    }

    private void intro() {
        on("hello", -0.5, client -> {
            mark("start");
            command(client,
                    "gamerule doDaylightCycle false",
                    "gamerule doWeatherCycle false",
                    "gamerule doMobSpawning false",
                    "tp @e[type=!minecraft:player] 0 -200 0",
                    "time set 6000",
                    "weather clear",
                    "difficulty easy",
                    "gamemode survival @a",
                    "clear @a",
                    "tp @a " + HOVER.x + " " + HOVER.y + " " + HOVER.z + " 0 " + VIEW_PITCH,
                    mob("husk", -6, 9, ""), mob("husk", 5, 11, ""), mob("pig", -2, 13, ""),
                    mob("sheep", 2, 7, ""), mob("pig", 8, 6, ""));
            giveArrow(client, 0, "rainbow");
            server(client, server -> {
                // Выживание — чтобы были видны сердечки, но игрок висит в воздухе и неуязвим.
                ServerPlayerEntity player = player(server);
                player.getAbilities().allowFlying = true;
                player.getAbilities().flying = true;
                player.getAbilities().invulnerable = true;
                player.sendAbilitiesUpdate();
            });
            client.options.setPerspective(Perspective.THIRD_PERSON_BACK);
            client.inGameHud.getChatHud().clear(false);
            this.caption = Text.literal("Обзор мода «Стрела Андрюшки»");
            this.snapCamera(-35.0F, 22.0F);
        });
        on("hello", 0.0, client -> command(client, "kill @e[type=minecraft:item]", "kill @e[type=minecraft:experience_orb]"));
        on("hello", 1.0, client -> this.pan(-35.0F, 35.0F, 26.0));
    }

    private void crafting() {
        on("craft", -0.5, client -> {
            this.caption = Text.literal("Крафт: аметист, редстоун, стрела и алмаз");
            this.look(0.0F, VIEW_PITCH);
        });
        on("craft", 0.5, client -> openCrafting(client, new ItemStack[] {
                ItemStack.EMPTY, new ItemStack(Items.AMETHYST_SHARD), ItemStack.EMPTY,
                new ItemStack(Items.REDSTONE), new ItemStack(Items.ARROW), new ItemStack(Items.REDSTONE),
                ItemStack.EMPTY, new ItemStack(Items.DIAMOND), ItemStack.EMPTY}));
        on("craft2", 0.0, client -> this.tooltip = Tooltip.CRAFT_RESULT);
        on("craft3", 6.5, client -> {
            this.tooltip = Tooltip.NONE;
            closeCrafting(client);
        });
    }

    private void targetMode() {
        on("target", -0.5, client -> {
            this.caption = Text.literal("Режим 1 — «Выбранная цель»");
            clearStage(client);
            command(client, mob("husk", -3, 5, "Цель"), mob("husk", 3, 6, ""), mob("pig", 0, 9, ""),
                    mob("sheep", -1, 11, ""));
        });
        on("target", 1.5, client -> this.lookAt(client, new Vec3d(-2.5, GROUND + 1.0, 5.5)));
        on("launch1", -1.0, AndryushkaArrowDemo::selectArrow);
        on("launch1", -0.2, client -> {
            this.useArrow(client);
            this.follow = true;
        });
        on("target4", 0.0, client -> this.look(0.0F, VIEW_PITCH));
        on("whistle", -0.5, client -> this.caption = Text.literal("Свист (клавиша G) — стрела возвращается"));
        on("whistle", 2.6, client -> press(ArrowKeyBindings.WHISTLE));
    }

    private void allMode() {
        on("all", -0.3, client -> {
            this.caption = Text.literal("Режим 2 — «Все мобы в радиусе 50 блоков»");
            command(client, "fill 2 " + GROUND + " 10 9 " + (GROUND + 3) + " 10 minecraft:stone_bricks");
            for (int i = 0; i < 6; i++) {
                command(client, mob("husk", 3 + (i % 3) * 2, 13 + (i / 3) * 2, ""));
            }
            command(client, mob("pig", -6, 8, ""), mob("pig", -4, 15, ""));
        });
        on("all", 2.0, client -> press(ArrowKeyBindings.TOGGLE_MODE));
        on("launch2", -1.0, AndryushkaArrowDemo::selectArrow);
        on("launch2", -0.2, this::useArrow);
        on("all3", 0.0, client -> this.caption = Text.literal("В толпе — до ×2 быстрее, урон прежний"));
        on("all6", -0.8, client -> press(ArrowKeyBindings.WHISTLE));
    }

    private void friends() {
        on("friends", -0.3, client -> {
            this.caption = Text.literal("Стрела щадит своих");
            clearStage(client);
            command(client, mob("villager", -4, 7, ""), mob("iron_golem", 4, 8, ""), mob("pig", 0, 6, "Хрюша"));
            for (int i = 0; i < 4; i++) {
                command(client, mob("husk", -3 + i * 2, 13, ""));
            }
            server(client, server -> {
                // Пёсик — приручённый игроком волк.
                ServerWorld world = server.getOverworld();
                WolfEntity wolf = EntityType.WOLF.create(world);
                if (wolf != null) {
                    wolf.refreshPositionAndAngles(-1.5, GROUND, 9.5, 180.0F, 0.0F);
                    wolf.setOwner(player(server));
                    wolf.setAiDisabled(true);
                    wolf.setPersistent();
                    world.spawnEntity(wolf);
                }
            });
        });
        on("friends2", 0.4, AndryushkaArrowDemo::selectArrow);
        on("friends2", 1.3, this::useArrow);
        on("friends4", 5.8, client -> press(ArrowKeyBindings.WHISTLE));
    }

    private void loot() {
        on("loot", -0.3, client -> {
            this.caption = Text.literal("Добыча летит прямо в инвентарь");
            clearStage(client);
            int[][] spots = {{-4, 8}, {-1, 10}, {2, 9}, {5, 7}};
            for (int[] spot : spots) {
                command(client, summon("husk", spot[0], spot[1], "NoAI:1b,PersistenceRequired:1b,"
                        + "HandItems:[{id:\"minecraft:diamond_sword\",count:1},{}],HandDropChances:[2.0f,0.0f],"
                        + "ArmorItems:[{},{},{},{id:\"minecraft:golden_helmet\",count:1}],"
                        + "ArmorDropChances:[0.0f,0.0f,0.0f,2.0f]"));
            }
        });
        on("loot2", -0.6, AndryushkaArrowDemo::selectArrow);
        on("loot2", 0.3, this::useArrow);
        on("loot3", 0.8, client -> client.setScreen(new InventoryScreen(client.player)));
        on("loot3", 7.6, client -> {
            client.player.closeHandledScreen();
            // Добычу убираем, чтобы стрела вернулась в первую ячейку.
            command(client, "clear @a minecraft:diamond_sword", "clear @a minecraft:golden_helmet",
                    "clear @a minecraft:rotten_flesh");
        });
        on("loot4", 6.0, client -> press(ArrowKeyBindings.WHISTLE));
    }

    private void guard() {
        on("guard", -0.3, client -> {
            this.caption = Text.literal("Режим 3 — «Охрана»");
            clearStage(client);
        });
        on("guard", 2.2, client -> press(ArrowKeyBindings.TOGGLE_MODE));
        on("guard2", -0.5, AndryushkaArrowDemo::selectArrow);
        on("guard2", 0.5, this::useArrow);
        on("guard3", -0.8, client -> {
            // Эти хаски живые: идут к игроку, а стрела встречает их на подходе.
            int[][] spots = {{-7, 22}, {-2, 25}, {3, 23}, {8, 21}, {0, 28}};
            for (int[] spot : spots) {
                command(client, summon("husk", spot[0], spot[1], "PersistenceRequired:1b"));
            }
        });
        on("guard4", -1.0, client -> command(client, summon("skeleton", 5, 11,
                "PersistenceRequired:1b,ArmorItems:[{},{},{},{id:\"minecraft:leather_helmet\",count:1}]")));
        on("guard6", 6.0, client -> press(ArrowKeyBindings.WHISTLE));
    }

    private void assist() {
        on("assist", -0.3, client -> {
            this.caption = Text.literal("Помогает в драке");
            clearStage(client);
        });
        on("assist", 1.0, client -> press(ArrowKeyBindings.TOGGLE_MODE));
        on("assist", 4.5, client -> this.look(0.0F, -35.0F));
        on("assist", 5.5, AndryushkaArrowDemo::selectArrow);
        on("assist", 6.5, this::useArrow);
        on("assist", 7.5, client -> {
            this.look(0.0F, VIEW_PITCH);
            // Хаск висит прямо перед игроком — до него можно дотянуться рукой.
            command(client, "summon minecraft:husk 0.5 " + (HOVER.y - 1.0) + " -0.4 {NoAI:1b,NoGravity:1b,"
                    + "PersistenceRequired:1b,Rotation:[180.0f,0.0f]}");
        });
        on("assist2", 2.5, client -> this.lookAt(client, new Vec3d(0.5, HOVER.y + 0.2, -0.4)));
        on("assist2", 4.0, this::punchNearestHusk);
        on("assist3", 4.3, client -> press(ArrowKeyBindings.WHISTLE));
    }

    private void levels() {
        on("level", -0.3, client -> {
            this.caption = Text.literal("Прокачка: уровни с I по V");
            clearStage(client);
            this.look(0.0F, VIEW_PITCH);
        });
        on("level2", 0.0, client -> {
            giveArrow(client, 14, "rainbow");
            this.tooltip = Tooltip.ARROW;
        });
        on("level2", 9.8, client -> this.tooltip = Tooltip.NONE);
        on("level3", 0.0, client -> command(client, mob("husk", 0, 7, "")));
        on("level3", 1.0, client -> this.lookAt(client, new Vec3d(0.5, GROUND + 1.0, 7.5)));
        on("level3", 3.2, AndryushkaArrowDemo::selectArrow);
        on("level3", 4.0, this::useArrow);
        on("level4", 5.6, client -> press(ArrowKeyBindings.WHISTLE));
        on("level5", 0.0, client -> {
            giveArrow(client, 149, "rainbow");
            command(client, mob("husk", 0, 8, ""), mob("husk", -3, 7, ""), mob("husk", 3, 7, ""));
            this.look(0.0F, VIEW_PITCH);
        });
        on("level5", 2.5, client -> this.lookAt(client, new Vec3d(0.5, GROUND + 1.0, 8.5)));
        on("level5", 4.2, AndryushkaArrowDemo::selectArrow);
        on("level5", 5.0, this::useArrow);
        on("level6", 6.8, client -> press(ArrowKeyBindings.WHISTLE));
        on("level7", 0.0, client -> {
            this.look(0.0F, VIEW_PITCH);
            this.tooltip = Tooltip.ARROW;
        });
        on("level7", 9.5, client -> this.tooltip = Tooltip.NONE);
    }

    private void combo() {
        on("combo", -0.3, client -> {
            this.caption = Text.literal("Серии побед — правило andryushkaArrowDamage 100");
            clearStage(client);
            command(client, "gamerule andryushkaArrowDamage 100");
        });
        on("combo", 1.5, client -> press(ArrowKeyBindings.TOGGLE_MODE));
        on("combo", 2.0, client -> {
            for (int i = 0; i < 10; i++) {
                command(client, mob("husk", -2 + i % 5, 8 + i / 5, ""));
            }
        });
        on("combo", 9.0, AndryushkaArrowDemo::selectArrow);
        on("combo", 10.0, this::useArrow);
        on("combo3", 6.0, client -> {
            command(client, "gamerule andryushkaArrowDamage 8");
            press(ArrowKeyBindings.WHISTLE);
        });
    }

    private void grinder() {
        on("grinder", -0.3, client -> {
            this.caption = Text.literal("Мясорубка: 20 хасков и стрела V уровня");
            clearStage(client);
            for (int i = 0; i < 20; i++) {
                double angle = 2 * Math.PI * i / 20;
                command(client, mob("husk", (int) Math.round(Math.cos(angle) * 9), 9 + (int) Math.round(Math.sin(angle) * 8), ""));
            }
        });
        on("grinder2", -1.8, AndryushkaArrowDemo::selectArrow);
        on("grinder2", -1.0, this::useArrow);
        on("grinder4", 2.5, client -> press(ArrowKeyBindings.WHISTLE));
    }

    private void trails() {
        on("trail", -0.3, client -> {
            this.caption = Text.literal("Цвет следа: стрела + краситель");
            clearStage(client);
        });
        on("trail", 0.3, client -> openCrafting(client, new ItemStack[] {
                ItemStack.EMPTY, ItemStack.EMPTY, ItemStack.EMPTY,
                ItemStack.EMPTY, arrowStack(150, ArrowTrail.RAINBOW), new ItemStack(Items.RED_DYE),
                ItemStack.EMPTY, ItemStack.EMPTY, ItemStack.EMPTY}));
        on("trail", 3.0, client -> this.tooltip = Tooltip.CRAFT_RESULT);
        on("trail2", 5.5, client -> {
            this.tooltip = Tooltip.NONE;
            closeCrafting(client);
        });
        for (String trail : TRAILS) {
            on(trail, -0.2, client -> {
                this.caption = Text.translatable("trail.andryushka_arrow." + trail);
                giveArrow(client, 150, trail);
                command(client, mob("husk", -3, 8, ""), mob("husk", 3, 10, ""));
                this.look(0.0F, VIEW_PITCH);
            });
            on(trail, 0.5, AndryushkaArrowDemo::selectArrow);
            on(trail, 1.0, this::useArrow);
            on(trail, 8.6, client -> press(ArrowKeyBindings.WHISTLE));
        }
    }

    private void advancements() {
        on("advancements", -0.3, client -> {
            this.caption = Text.literal("Своя вкладка достижений");
            clearStage(client);
        });
        on("advancements", 0.3, client -> {
            ClientAdvancementManager manager = client.getNetworkHandler().getAdvancementHandler();
            client.setScreen(new AdvancementsScreen(manager));
            AdvancementEntry root = manager.get(AndryushkaArrowMod.id("root"));
            if (root != null) {
                manager.selectTab(root, true);
            }
        });
        on("advancements2", 4.5, client -> client.setScreen(null));
    }

    private void finale() {
        on("rules", -0.3, client -> {
            this.caption = Text.literal("Настройки — правила игры");
            this.look(0.0F, VIEW_PITCH);
        });
        on("rules2", 0.3, client -> client.setScreen(new ChatScreen("/gamerule andryushkaArrowDuration 15")));
        on("rules2", 4.8, client -> {
            client.setScreen(null);
            command(client, "gamerule andryushkaArrowDuration 15");
            // Свежая стрела I уровня: у ветерана к 15 секундам добавились бы ещё 20.
            giveArrow(client, 0, "rainbow");
            for (int i = 0; i < 4; i++) {
                command(client, mob("husk", -5 + i * 3, 9 + (i % 2) * 3, ""));
            }
        });
        on("final", -0.8, AndryushkaArrowDemo::selectArrow);
        on("final", -0.2, client -> {
            this.caption = Text.literal("Финал: колокольчик, круг почёта и падение");
            this.useArrow(client);
        });
        on("landed", 1.5, client -> server(client, server -> {
            // Игрок спускается к упавшей стреле и подбирает её.
            ServerPlayerEntity player = player(server);
            List<ItemEntity> drops = player.getServerWorld().getEntitiesByClass(ItemEntity.class,
                    player.getBoundingBox().expand(24.0), item -> item.getStack().isOf(ModItems.ANDRYUSHKA_ARROW));
            if (!drops.isEmpty()) {
                ItemEntity drop = drops.get(0);
                player.getAbilities().flying = false;
                player.sendAbilitiesUpdate();
                player.requestTeleport(drop.getX(), drop.getY(), drop.getZ() - 0.8);
            }
        }));
        on("outro", -0.3, client -> {
            command(client, "gamerule andryushkaArrowDuration 40",
                    "tp @a " + HOVER.x + " " + HOVER.y + " " + HOVER.z + " 0 " + VIEW_PITCH);
            server(client, server -> {
                ServerPlayerEntity player = player(server);
                player.getAbilities().flying = true;
                player.sendAbilitiesUpdate();
            });
            this.caption = Text.literal("Спасибо за просмотр!");
            this.look(-30.0F, 6.0F);
        });
        on("outro", 0.5, client -> this.pan(-30.0F, 30.0F, 28.0));
        // Подальше и пониже — чтобы салют целиком помещался в кадр.
        for (int i = 0; i < 6; i++) {
            int x = -8 + i * 3;
            on("outro2", i * 1.4, client -> command(client, firework(x, 17 + Math.abs(x % 2) * 3)));
        }
    }

    // ---------------------------------------------------------------- действия

    private static String mob(String type, int x, int z, String name) {
        String tags = "NoAI:1b,PersistenceRequired:1b";
        if (!name.isEmpty()) {
            tags += ",CustomName:'\"" + name + "\"',CustomNameVisible:1b";
        }
        return summon(type, x, z, tags);
    }

    private static String summon(String type, int x, int z, String nbt) {
        return "summon minecraft:" + type + " " + (x + 0.5) + " " + GROUND + " " + (z + 0.5) + " {" + nbt + "}";
    }

    private static String firework(int x, int z) {
        return "summon minecraft:firework_rocket " + (x + 0.5) + " " + (GROUND + 1) + " " + (z + 0.5)
                + " {LifeTime:22,FireworksItem:{id:\"minecraft:firework_rocket\",count:1,components:{"
                + "\"minecraft:fireworks\":{flight_duration:1,explosions:[{shape:\"large_ball\","
                + "colors:[I;16711680,16760576,65280,255,16711935],has_twinkle:1b,has_trail:1b}]}}}}";
    }

    private static String arrowId(int kills, String trail) {
        return "andryushka_arrow:andryushka_arrow[andryushka_arrow:kills=" + kills + ",andryushka_arrow:trail=\"" + trail + "\"]";
    }

    /** Свежая стрела в первую ячейку — с нужным числом побед и цветом следа. */
    private static void giveArrow(MinecraftClient client, int kills, String trail) {
        command(client, "clear @a", "item replace entity @a hotbar.0 with " + arrowId(kills, trail));
        server(client, server -> {
            ServerPlayerEntity player = player(server);
            player.getInventory().selectedSlot = 0;
            player.networkHandler.sendPacket(new UpdateSelectedSlotS2CPacket(0));
        });
    }

    private static ItemStack arrowStack(int kills, ArrowTrail trail) {
        ItemStack stack = new ItemStack(ModItems.ANDRYUSHKA_ARROW);
        stack.set(ModComponents.KILLS, kills);
        stack.set(ModComponents.TRAIL, trail);
        return stack;
    }

    /**
     * Убирает мобов, предметы и стену; стрелу в полёте не трогает. Мобов не убиваем, а отправляем
     * в пустоту под миром: после /kill из них выпал бы лут и остался лежать на поле.
     */
    private static void clearStage(MinecraftClient client) {
        command(client,
                "tp @e[type=!minecraft:player,type=!andryushka_arrow:andryushka_arrow] 0 -200 0",
                "fill -14 " + GROUND + " 2 14 " + (GROUND + 4) + " 30 minecraft:air");
    }

    /**
     * Берёт в руку стрелу: после возврата она могла лечь не в первую ячейку (добыча успела занять
     * её). Ищем на панели быстрого доступа, а если там нет — переносим из инвентаря в первую.
     */
    private static void selectArrow(MinecraftClient client) {
        server(client, server -> {
            ServerPlayerEntity player = player(server);
            PlayerInventory inventory = player.getInventory();
            int found = -1;
            for (int slot = 0; slot < inventory.main.size() && found < 0; slot++) {
                if (inventory.getStack(slot).isOf(ModItems.ANDRYUSHKA_ARROW)) {
                    found = slot;
                }
            }
            if (found < 0) {
                return;
            }
            if (found >= PlayerInventory.getHotbarSize()) {
                ItemStack first = inventory.getStack(0);
                inventory.setStack(0, inventory.getStack(found));
                inventory.setStack(found, first);
                found = 0;
            }
            inventory.selectedSlot = found;
            player.networkHandler.sendPacket(new UpdateSelectedSlotS2CPacket(found));
        });
    }

    /** ПКМ с предметом в руке — тот же путь, что у игрока. */
    private void useArrow(MinecraftClient client) {
        if (client.interactionManager != null && client.player != null) {
            client.interactionManager.interactItem(client.player, Hand.MAIN_HAND);
            client.player.swingHand(Hand.MAIN_HAND);
        }
    }

    /** Удар рукой по ближайшему хаску — свободная стрела приходит на помощь. */
    private void punchNearestHusk(MinecraftClient client) {
        ClientPlayerEntity player = client.player;
        HuskEntity nearest = null;
        for (HuskEntity husk : client.world.getEntitiesByClass(HuskEntity.class, player.getBoundingBox().expand(5.0),
                husk -> husk.isAlive())) {
            if (nearest == null || husk.squaredDistanceTo(player) < nearest.squaredDistanceTo(player)) {
                nearest = husk;
            }
        }
        if (nearest != null) {
            client.interactionManager.attackEntity(player, nearest);
            player.swingHand(Hand.MAIN_HAND);
        }
    }

    /** Нажатие клавиши: мод обработает его в своём обработчике тика, как настоящее. */
    private static void press(KeyBinding binding) {
        KeyBinding.onKeyPressed(KeyBindingHelper.getBoundKeyOf(binding));
    }

    /** Настоящий верстак с разложенным рецептом: сервер сам посчитает результат. */
    private static void openCrafting(MinecraftClient client, ItemStack[] grid) {
        server(client, server -> {
            ServerPlayerEntity player = player(server);
            ServerWorld world = player.getServerWorld();
            world.setBlockState(TABLE, Blocks.CRAFTING_TABLE.getDefaultState());
            player.openHandledScreen(new SimpleNamedScreenHandlerFactory((syncId, inventory, who) ->
                    new CraftingScreenHandler(syncId, inventory, ScreenHandlerContext.create(world, TABLE)),
                    Text.translatable("container.crafting")));
            if (player.currentScreenHandler instanceof CraftingScreenHandler handler) {
                for (int i = 0; i < grid.length; i++) {
                    handler.getSlot(1 + i).setStack(grid[i].copy());
                }
            }
        });
    }

    /** Закрывает верстак, предварительно убрав всё с сетки, чтобы ничего не выпало игроку. */
    private static void closeCrafting(MinecraftClient client) {
        server(client, server -> {
            ServerPlayerEntity player = player(server);
            if (player.currentScreenHandler instanceof CraftingScreenHandler handler) {
                for (int i = 1; i <= 9; i++) {
                    handler.getSlot(i).setStack(ItemStack.EMPTY);
                }
            }
            player.closeHandledScreen();
            player.getServerWorld().setBlockState(TABLE, Blocks.AIR.getDefaultState());
        });
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
                // Все рецепты сразу: иначе посреди ролика всплывали бы «Новые рецепты».
                command(client, "recipe give @a *");
            }
            if (this.warmup == SLOWDOWN_TICK && TICK_RATE != 20.0F) {
                command(client, String.format(Locale.ROOT, "tick rate %.4f", TICK_RATE));
            }
            client.getToastManager().clear();
            this.warmup++;
            return;
        }
        this.tick++;
        for (Step step : this.script) {
            if (step.tick() == this.tick) {
                step.action().accept(client);
            }
        }
        // Достижения мода всплывают — это часть обзора; первые секунды чистим остатки от загрузки.
        // В апдейт-ролике всплывашки не нужны совсем: кадры монтируются без них.
        if (this.tick < 40 || UPDATE_VIDEO) {
            client.getToastManager().clear();
        }
        this.steer(client);
        this.logLoopsAndCamera(client);
        if (this.tick % 200 == 0) {
            AndryushkaArrowMod.LOGGER.info("Демо: секунда {} из {}, {} кадров/с", this.tick / 20, this.endTick / 20,
                    client.getCurrentFps());
        }
    }

    private static float tickRateFromEnvironment() {
        String value = System.getenv("ANDRYUSHKA_DEMO_TICK_RATE");
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
        // Ручной волк, убранный со сцены в пустоту, иначе напишет в чат «Волк выпал из мира».
        rules.get(GameRules.SHOW_DEATH_MESSAGES).set(false, null);
        // Иначе в чате видно, как сценарий замедляет игру и раздаёт предметы.
        rules.get(GameRules.SEND_COMMAND_FEEDBACK).set(false, null);
        rules.get(GameRules.LOG_ADMIN_COMMANDS).set(false, null);
        LevelInfo info = new LevelInfo("andryushka-demo", GameMode.CREATIVE, false, Difficulty.EASY, true,
                rules, DataConfiguration.SAFE_MODE);
        client.createIntegratedServerLoader().createAndStart("andryushka-demo", info,
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
        this.panStart = -1;
    }

    /** Сразу смотреть туда, без плавного поворота, — для первого кадра ролика. */
    private void snapCamera(float yaw, float pitch) {
        this.look(yaw, pitch);
        this.cameraYaw = this.prevCameraYaw = yaw;
        this.cameraPitch = this.prevCameraPitch = pitch;
        this.cameraReady = true;
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

    /** Медленная панорама по горизонтали — для вступления и концовки. */
    private void pan(float fromYaw, float toYaw, double seconds) {
        this.panFrom = fromYaw;
        this.panTo = toYaw;
        this.panStart = this.tick;
        this.panTicks = Math.max(1, (int) Math.round(seconds * 20.0));
        this.steering = true;
    }

    /**
     * Плавный поворот головы — чтобы в ролике не было рывков. Пока стрела охотится, камера
     * смотрит на середину между ней и её целью, иначе — туда, куда велит сценарий.
     */
    private void steer(MinecraftClient client) {
        ClientPlayerEntity player = client.player;
        if (!this.steering || client.currentScreen != null) {
            return;
        }
        if (!this.cameraReady) {
            this.cameraYaw = player.getYaw();
            this.cameraPitch = player.getPitch();
            this.cameraReady = true;
        }
        this.prevCameraYaw = this.cameraYaw;
        this.prevCameraPitch = this.cameraPitch;
        if (this.panStart >= 0) {
            float t = MathHelper.clamp((this.tick - this.panStart) / (float) this.panTicks, 0.0F, 1.0F);
            float eased = t * t * (3.0F - 2.0F * t);
            this.lookYaw = MathHelper.lerp(eased, this.panFrom, this.panTo);
        }
        float wantYaw = this.lookYaw;
        float wantPitch = this.lookPitch;
        AndryushkaArrowEntity arrow = ClientArrowState.activeArrow();
        if (this.follow && arrow != null && arrow.getStatus() == AndryushkaArrowEntity.STATUS_HUNTING) {
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
        this.cameraYaw += MathHelper.wrapDegrees(wantYaw - this.cameraYaw) * STEER_SMOOTHING;
        this.cameraPitch += (wantPitch - this.cameraPitch) * STEER_SMOOTHING;
    }

    /**
     * Камера своего игрока в Minecraft не сглаживается между тиками: поворот мышью и так приходит
     * в каждом кадре. Поверни её сценарий раз в тик — в замедленной игре она шла бы ступеньками,
     * 20 раз в секунду ролика. Поэтому угол между тиками считаем в каждом кадре сами (он вступает
     * в силу со следующего кадра — на глаз это незаметно).
     */
    private void applyCamera() {
        MinecraftClient client = MinecraftClient.getInstance();
        ClientPlayerEntity player = client.player;
        if (!this.cameraReady || !this.steering || player == null || client.currentScreen != null) {
            return;
        }
        float tickDelta = client.getRenderTickCounter().getTickDelta(true);
        float yaw = MathHelper.lerpAngleDegrees(tickDelta, this.prevCameraYaw, this.cameraYaw);
        float pitch = MathHelper.lerp(tickDelta, this.prevCameraPitch, this.cameraPitch);
        player.setYaw(yaw);
        player.setPitch(pitch);
        player.setHeadYaw(yaw);
        player.setBodyYaw(yaw);
        // Фигура игрока рисуется между углами прошлого и этого тика — ставим оба, чтобы она
        // поворачивалась вместе с камерой.
        player.prevYaw = yaw;
        player.prevPitch = pitch;
        player.prevHeadYaw = yaw;
        player.prevBodyYaw = yaw;
    }

    // ---------------------------------------------------------------- надписи поверх игры

    private void renderOverlay(DrawContext context, RenderTickCounter tickCounter) {
        if (this.tick < 0) {
            return;
        }
        MinecraftClient client = MinecraftClient.getInstance();
        TextRenderer font = client.textRenderer;
        if (!this.caption.getString().isEmpty()) {
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
        if (this.tooltip == Tooltip.ARROW && client.player != null) {
            ItemStack arrow = findArrow(client.player.getInventory());
            if (!arrow.isEmpty()) {
                context.drawItemTooltip(font, arrow, 12, 50);
            }
        }
    }

    /** На экране верстака — подсказка к результату крафта, как при наведении мыши. */
    private void renderScreenOverlay(Screen screen, DrawContext context, int mouseX, int mouseY, float tickDelta) {
        if (this.tooltip != Tooltip.CRAFT_RESULT || !(screen instanceof CraftingScreen crafting)) {
            return;
        }
        ItemStack result = crafting.getScreenHandler().getSlot(0).getStack();
        if (!result.isEmpty()) {
            int x = (screen.width - 176) / 2 + 124 + 8;
            int y = (screen.height - 166) / 2 + 35 + 8;
            context.drawItemTooltip(MinecraftClient.getInstance().textRenderer, result, x, y);
        }
    }

    private static ItemStack findArrow(PlayerInventory inventory) {
        for (int slot = 0; slot < inventory.size(); slot++) {
            if (inventory.getStack(slot).isOf(ModItems.ANDRYUSHKA_ARROW)) {
                return inventory.getStack(slot);
            }
        }
        return ItemStack.EMPTY;
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
            AndryushkaArrowMod.LOGGER.warn("Не удалось записать журнал звуков", e);
        }
    }

    private void closeSoundLog() {
        this.soundLogClosed = true;
        if (this.soundLog != null) {
            this.soundLog.close();
        }
    }

    // ---------------------------------------------------------------- служебное

    private static ServerPlayerEntity player(MinecraftServer server) {
        return server.getPlayerManager().getPlayerList().get(0);
    }

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
            AndryushkaArrowMod.LOGGER.warn("Не удалось записать отметку {}", name, e);
        }
    }
}
