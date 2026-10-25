package com.andryushka.arrow.client;

import com.andryushka.arrow.ArrowLevel;
import com.andryushka.arrow.ArrowMode;
import com.andryushka.arrow.entity.AndryushkaArrowEntity;
import com.andryushka.arrow.registry.ModComponents;
import com.andryushka.arrow.registry.ModItems;
import com.andryushka.arrow.registry.ModSounds;
import java.util.UUID;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import org.jetbrains.annotations.Nullable;

/** Что клиент знает о своей стреле: режим, летящая стрела и есть ли она в инвентаре. */
public final class ClientArrowState {
    private static ArrowMode mode = ArrowMode.TARGET;
    /** Давно в прошлом — чтобы при входе в мир надпись режима не мигнула. */
    private static final long LONG_AGO = Long.MIN_VALUE / 2;
    private static long modeChangedAt = LONG_AGO;
    @Nullable
    private static AndryushkaArrowEntity activeArrow;
    private static boolean hasArrowItem;
    private static int trackedArrowId = -1;
    private static int trackedKills;
    private static int trackedCombo;
    private static int trackedLevel;
    private static long killedAt = LONG_AGO;
    /** Рок серии играет до этого момента: окно серии после последней победы и ещё немного. */
    private static final long ROCK_HOLD_MILLIS = AndryushkaArrowEntity.COMBO_WINDOW_TICKS * 50L + 1000L;
    private static long rockingUntil = LONG_AGO;
    @Nullable
    private static ComboRockSound rock;
    /** Визг гитары на победу серии — по ступеням ми-минорной пентатоники, в лад року: ми, соль, ля, си, ре, ми. */
    private static final int[] LICK_SEMITONES = {0, 3, 5, 7, 10, 12};
    /** Наибольший уровень среди стрел в инвентаре — для надписи, пока стрела не летит. */
    private static int itemLevel = 1;
    @Nullable
    private static Popup popup;

    /** Крупная надпись над прицелом: серия побед или новый уровень стрелы. */
    public record Popup(Text title, @Nullable Text subtitle, boolean levelUp, long startedAt) {
    }

    private ClientArrowState() {
    }

    public static ArrowMode mode() {
        return mode;
    }

    public static void setMode(ArrowMode newMode) {
        if (newMode != mode) {
            mode = newMode;
            modeChangedAt = gameMillis();
        }
    }

    /** Миллисекунды с последней смены режима — для короткой подсветки надписи. */
    public static long millisSinceModeChange() {
        long since = gameMillis() - modeChangedAt;
        // В другом мире своё игровое время: отметка из прошлого мира может оказаться «в будущем».
        return since >= 0L ? since : Long.MAX_VALUE;
    }

    /**
     * Игровое время в миллисекундах — часы для анимаций HUD. Идёт вместе с тиками мира,
     * поэтому при замедлении игры командой /tick rate анимации замедляются вместе с ней.
     */
    public static long gameMillis() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world == null) {
            return 0L;
        }
        return Math.round((client.world.getTime() + client.getRenderTickCounter().getTickDelta(false)) * 50.0);
    }

    /** Миллисекунды с последней победы стрелы — счётчик на миг подсвечивается. */
    public static long millisSinceKill() {
        long since = gameMillis() - killedAt;
        return since >= 0L ? since : Long.MAX_VALUE;
    }

    @Nullable
    public static AndryushkaArrowEntity activeArrow() {
        return activeArrow;
    }

    public static boolean hasArrowItem() {
        return hasArrowItem;
    }

    /** Уровень стрелы для надписи: летящей, а если она не летит — лучшей из инвентаря. */
    public static int level() {
        return activeArrow != null ? activeArrow.getLevel() : itemLevel;
    }

    @Nullable
    public static Popup popup() {
        return popup;
    }

    /** Идёт серия побед — играет рок. */
    public static boolean isRocking() {
        return gameMillis() < rockingUntil;
    }

    /** Насколько громко сейчас играет рок серии (0–1): бас полёта на это время уходит. */
    public static float rockLevel() {
        return rock != null && !rock.isDone() ? rock.level() : 0.0F;
    }

    public static void tick(MinecraftClient client) {
        activeArrow = null;
        hasArrowItem = false;
        if (client.player == null || client.world == null) {
            return;
        }
        UUID self = client.player.getUuid();
        for (Entity entity : client.world.getEntities()) {
            if (entity instanceof AndryushkaArrowEntity arrow && arrow.isOwnedBy(self)
                    && (activeArrow == null || arrow.getSyncedLife() > activeArrow.getSyncedLife())) {
                activeArrow = arrow;
            }
        }
        trackProgress(client);
        itemLevel = 1;
        PlayerInventory inventory = client.player.getInventory();
        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (stack.isOf(ModItems.ANDRYUSHKA_ARROW)) {
                hasArrowItem = true;
                itemLevel = Math.max(itemLevel, ArrowLevel.of(stack.getOrDefault(ModComponents.KILLS, 0)));
            }
        }
    }

    /** Победы, серии и уровень летящей стрелы: на каждое изменение — вспышка счётчика или надпись. */
    private static void trackProgress(MinecraftClient client) {
        if (activeArrow == null) {
            trackedArrowId = -1;
            return;
        }
        int kills = activeArrow.getKills();
        int combo = activeArrow.getCombo();
        int level = activeArrow.getLevel();
        if (activeArrow.getId() == trackedArrowId) {
            if (kills > trackedKills) {
                killedAt = gameMillis();
            }
            if (combo > trackedCombo && combo >= 2) {
                showCombo(client, combo);
            }
            if (level > trackedLevel) {
                popup = new Popup(Text.translatable("popup.andryushka_arrow.level", ArrowLevel.roman(level)),
                        Text.translatable("popup.andryushka_arrow.level.subtitle"), true, gameMillis());
            }
        }
        trackedArrowId = activeArrow.getId();
        trackedKills = kills;
        trackedCombo = combo;
        trackedLevel = level;
    }

    private static void showCombo(MinecraftClient client, int combo) {
        Text title = combo <= 5
                ? Text.translatable("popup.andryushka_arrow.combo." + combo)
                : Text.translatable("popup.andryushka_arrow.combo.more", combo);
        popup = new Popup(title, null, false, gameMillis());
        // Со второй победы подряд вступает рок, а гитара с каждой победой серии взвизгивает выше.
        rockingUntil = gameMillis() + ROCK_HOLD_MILLIS;
        if (rock == null || rock.isDone()) {
            rock = new ComboRockSound();
            client.getSoundManager().play(rock);
        }
        int semitones = LICK_SEMITONES[Math.min(combo - 2, LICK_SEMITONES.length - 1)];
        float pitch = (float) Math.pow(2.0, semitones / 12.0);
        client.getSoundManager().play(PositionedSoundInstance.master(ModSounds.COMBO_LICK, pitch, 0.6F));
    }

    public static void reset() {
        mode = ArrowMode.TARGET;
        modeChangedAt = LONG_AGO;
        killedAt = LONG_AGO;
        trackedArrowId = -1;
        popup = null;
        rockingUntil = LONG_AGO;
        rock = null;
        itemLevel = 1;
        activeArrow = null;
        hasArrowItem = false;
    }
}
