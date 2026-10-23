package com.olegus.arrow.client;

import com.olegus.arrow.OlegusArrowMod;
import com.olegus.arrow.Rainbow;
import com.olegus.arrow.entity.OlegusArrowEntity;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.model.ModelData;
import net.minecraft.client.model.ModelPart;
import net.minecraft.client.model.ModelPartBuilder;
import net.minecraft.client.model.ModelPartData;
import net.minecraft.client.model.ModelTransform;
import net.minecraft.client.model.TexturedModelData;
import net.minecraft.client.render.LightmapTextureManager;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.render.entity.model.EntityModelLayer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.RotationAxis;
import net.minecraft.util.math.Vec3d;

/**
 * Стрела Яка: тонкое красное древко, ступенчатый наконечник и крестовое оперение,
 * вокруг — мягкое переливающееся свечение, позади — светящаяся радужная лента.
 */
public class OlegusArrowRenderer extends EntityRenderer<OlegusArrowEntity> {
    public static final EntityModelLayer MODEL_LAYER = new EntityModelLayer(OlegusArrowMod.id("olegus_arrow"), "main");

    private static final Identifier TEXTURE = OlegusArrowMod.id("textures/entity/olegus_arrow.png");
    private static final Identifier GLOW_TEXTURE = OlegusArrowMod.id("textures/entity/olegus_arrow_glow.png");
    private static final Identifier TRAIL_TEXTURE = OlegusArrowMod.id("textures/entity/olegus_arrow_trail.png");
    private static final int FULL_BRIGHT = LightmapTextureManager.MAX_LIGHT_COORDINATE;
    private static final float MODEL_SCALE = 0.9F;
    /** Наибольшая полуширина ленты следа, в блоках. */
    private static final double TRAIL_HALF_WIDTH = 0.13;
    /** Лента начинается от хвоста стрелы, а не из её середины. */
    private static final double TRAIL_START_BEHIND = 0.42;
    /** На скольких первых точках лента плавно расширяется от хвоста. */
    private static final int TRAIL_WIDEN_POINTS = 4;

    private final ModelPart model;

    public OlegusArrowRenderer(EntityRendererFactory.Context context) {
        super(context);
        this.shadowRadius = 0.0F;
        this.model = context.getPart(MODEL_LAYER);
    }

    /** Модель вдоль оси +Z (остриё спереди), размеры в пикселях — 1/16 блока. */
    public static TexturedModelData getTexturedModelData() {
        ModelData data = new ModelData();
        ModelPartData root = data.getRoot();
        root.addChild("shaft", ModelPartBuilder.create().uv(0, 0)
                .cuboid(-0.5F, -0.5F, -8.5F, 1.0F, 1.0F, 12.0F), ModelTransform.NONE);
        root.addChild("head_base", ModelPartBuilder.create().uv(0, 14)
                .cuboid(-1.25F, -1.25F, 3.0F, 2.5F, 2.5F, 1.5F), ModelTransform.NONE);
        root.addChild("head_mid", ModelPartBuilder.create().uv(8, 14)
                .cuboid(-0.9F, -0.9F, 4.5F, 1.8F, 1.8F, 1.5F), ModelTransform.NONE);
        root.addChild("head_front", ModelPartBuilder.create().uv(16, 14)
                .cuboid(-0.55F, -0.55F, 6.0F, 1.1F, 1.1F, 1.5F), ModelTransform.NONE);
        root.addChild("tip", ModelPartBuilder.create().uv(24, 14)
                .cuboid(-0.25F, -0.25F, 7.5F, 0.5F, 0.5F, 1.25F), ModelTransform.NONE);
        // Оперение крестом: сзади повыше, ближе к середине пониже — так оно выглядит отогнутым назад.
        root.addChild("fin_back_vertical", ModelPartBuilder.create().uv(28, 0)
                .cuboid(-0.15F, -1.3F, -9.0F, 0.3F, 2.6F, 2.5F), ModelTransform.NONE);
        root.addChild("fin_back_horizontal", ModelPartBuilder.create().uv(36, 0)
                .cuboid(-1.3F, -0.15F, -9.0F, 2.6F, 0.3F, 2.5F), ModelTransform.NONE);
        root.addChild("fin_front_vertical", ModelPartBuilder.create().uv(28, 8)
                .cuboid(-0.15F, -0.85F, -6.5F, 0.3F, 1.7F, 2.0F), ModelTransform.NONE);
        root.addChild("fin_front_horizontal", ModelPartBuilder.create().uv(36, 5)
                .cuboid(-0.85F, -0.15F, -6.5F, 1.7F, 0.3F, 2.0F), ModelTransform.NONE);
        root.addChild("nock", ModelPartBuilder.create().uv(50, 0)
                .cuboid(-0.6F, -0.6F, -9.5F, 1.2F, 1.2F, 0.5F), ModelTransform.NONE);
        return TexturedModelData.of(data, 64, 32);
    }

    @Override
    public void render(OlegusArrowEntity arrow, float yaw, float tickDelta, MatrixStack matrices,
                       VertexConsumerProvider vertexConsumers, int light) {
        float time = arrow.age + tickDelta;
        float arrowYaw = MathHelper.lerpAngleDegrees(tickDelta, arrow.prevYaw, arrow.getYaw());
        float arrowPitch = MathHelper.lerp(tickDelta, arrow.prevPitch, arrow.getPitch());
        double yawRad = Math.toRadians(arrowYaw);
        double pitchRad = Math.toRadians(arrowPitch);
        Vec3d forward = new Vec3d(Math.sin(yawRad) * Math.cos(pitchRad), Math.sin(pitchRad),
                Math.cos(yawRad) * Math.cos(pitchRad));
        this.renderTrail(arrow, tickDelta, time, forward, matrices, vertexConsumers);

        matrices.push();
        matrices.translate(0.0F, arrow.getHeight() / 2.0F, 0.0F);

        matrices.push();
        // Те же углы, что у ванильной стрелы: yaw = atan2(vx, vz), pitch вверх положительный.
        matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(arrowYaw));
        matrices.multiply(RotationAxis.POSITIVE_X.rotationDegrees(-arrowPitch));
        matrices.scale(MODEL_SCALE, MODEL_SCALE, MODEL_SCALE);
        this.model.render(matrices, vertexConsumers.getBuffer(RenderLayer.getEntityCutout(TEXTURE)),
                FULL_BRIGHT, OverlayTexture.DEFAULT_UV);
        matrices.pop();

        // Свечение: яркое у наконечника и мягкое вокруг всей стрелы, оттенок медленно переливается.
        float pulse = 0.85F + 0.15F * MathHelper.sin(time * 0.3F);
        int tipColor = Rainbow.hsvToRgb(time * 0.01F, 0.45F, 1.0F);
        this.renderGlow(matrices, vertexConsumers, forward.multiply(0.42), 0.55F * pulse, tipColor, 0.9F);
        this.renderGlow(matrices, vertexConsumers, Vec3d.ZERO, 1.15F, 0xFF3040, 0.35F * pulse);
        matrices.pop();

        super.render(arrow, yaw, tickDelta, matrices, vertexConsumers, light);
    }

    /** Плоский светящийся диск, всегда повёрнутый к камере. */
    private void renderGlow(MatrixStack matrices, VertexConsumerProvider vertexConsumers, Vec3d offset, float size,
                            int rgb, float intensity) {
        matrices.push();
        matrices.translate(offset.x, offset.y, offset.z);
        matrices.multiply(this.dispatcher.getRotation());
        MatrixStack.Entry entry = matrices.peek();
        VertexConsumer consumer = vertexConsumers.getBuffer(RenderLayer.getEyes(GLOW_TEXTURE));
        int r = Math.round(((rgb >> 16) & 0xFF) * intensity);
        int g = Math.round(((rgb >> 8) & 0xFF) * intensity);
        int b = Math.round((rgb & 0xFF) * intensity);
        float h = size / 2.0F;
        // Обе стороны, чтобы отсечение граней не съело свечение.
        vertex(entry, consumer, -h, -h, 0.0F, 0.0F, 1.0F, r, g, b);
        vertex(entry, consumer, h, -h, 0.0F, 1.0F, 1.0F, r, g, b);
        vertex(entry, consumer, h, h, 0.0F, 1.0F, 0.0F, r, g, b);
        vertex(entry, consumer, -h, h, 0.0F, 0.0F, 0.0F, r, g, b);
        vertex(entry, consumer, -h, h, 0.0F, 0.0F, 0.0F, r, g, b);
        vertex(entry, consumer, h, h, 0.0F, 1.0F, 0.0F, r, g, b);
        vertex(entry, consumer, h, -h, 0.0F, 1.0F, 1.0F, r, g, b);
        vertex(entry, consumer, -h, -h, 0.0F, 0.0F, 1.0F, r, g, b);
        matrices.pop();
    }

    /**
     * Лента следа по последним позициям стрелы. Каждый участок развёрнут к камере, сужается
     * и гаснет к хвосту, а радуга бежит вдоль ленты — так след переливается.
     */
    private void renderTrail(OlegusArrowEntity arrow, float tickDelta, float time, Vec3d forward, MatrixStack matrices,
                             VertexConsumerProvider vertexConsumers) {
        List<Vec3d> history = arrow.getTrailHistory();
        if (history.size() < 3) {
            return;
        }
        Vec3d origin = new Vec3d(MathHelper.lerp(tickDelta, arrow.prevX, arrow.getX()),
                MathHelper.lerp(tickDelta, arrow.prevY, arrow.getY()),
                MathHelper.lerp(tickDelta, arrow.prevZ, arrow.getZ()));
        // Первая точка — хвост стрелы там, где она нарисована сейчас; самая свежая позиция
        // из истории ещё впереди нарисованной стрелы, поэтому её пропускаем.
        List<Vec3d> points = new ArrayList<>(history.size());
        points.add(origin.add(0.0, arrow.getHeight() / 2.0, 0.0).subtract(forward.multiply(TRAIL_START_BEHIND)));
        points.addAll(history.subList(1, history.size()));
        int count = points.size();

        Vec3d camera = this.dispatcher.camera.getPos();
        double widthScale = 0.85 + 0.3 * (arrow.getBoost() - 1.0);
        Vec3d[] sides = new Vec3d[count];
        float[] fades = new float[count];
        for (int i = 0; i < count; i++) {
            fades[i] = (float) Math.pow(1.0 - i / (double) (count - 1), 1.3);
            Vec3d along = points.get(Math.min(i + 1, count - 1)).subtract(points.get(Math.max(i - 1, 0)));
            Vec3d side = along.crossProduct(points.get(i).subtract(camera));
            double length = side.length();
            double widen = Math.min(1.0, (i + 1) / (double) TRAIL_WIDEN_POINTS);
            double halfWidth = TRAIL_HALF_WIDTH * widthScale * widen * (0.25 + 0.75 * fades[i]);
            if (length > 1.0E-6) {
                sides[i] = side.multiply(halfWidth / length);
            } else {
                sides[i] = i > 0 ? sides[i - 1] : new Vec3d(0.0, halfWidth, 0.0);
            }
        }

        VertexConsumer consumer = vertexConsumers.getBuffer(RenderLayer.getEyes(TRAIL_TEXTURE));
        MatrixStack.Entry entry = matrices.peek();
        for (int i = 0; i < count - 1; i++) {
            Vec3d a = points.get(i).subtract(origin);
            Vec3d b = points.get(i + 1).subtract(origin);
            int colorA = trailColor(i, time, fades[i]);
            int colorB = trailColor(i + 1, time, fades[i + 1]);
            float uA = i / (float) (count - 1);
            float uB = (i + 1) / (float) (count - 1);
            Vec3d a1 = a.add(sides[i]);
            Vec3d a2 = a.subtract(sides[i]);
            Vec3d b1 = b.add(sides[i + 1]);
            Vec3d b2 = b.subtract(sides[i + 1]);
            trailVertex(entry, consumer, a1, uA, 0.0F, colorA);
            trailVertex(entry, consumer, a2, uA, 1.0F, colorA);
            trailVertex(entry, consumer, b2, uB, 1.0F, colorB);
            trailVertex(entry, consumer, b1, uB, 0.0F, colorB);
            trailVertex(entry, consumer, b1, uB, 0.0F, colorB);
            trailVertex(entry, consumer, b2, uB, 1.0F, colorB);
            trailVertex(entry, consumer, a2, uA, 1.0F, colorA);
            trailVertex(entry, consumer, a1, uA, 0.0F, colorA);
        }
    }

    /** Цвет точки ленты; яркость уже умножена на затухание (слой складывает цвета). */
    private static int trailColor(int index, float time, float fade) {
        int rgb = Rainbow.hsvToRgb(index * 0.055F - time * 0.025F, 0.85F, 1.0F);
        int r = Math.round(((rgb >> 16) & 0xFF) * fade);
        int g = Math.round(((rgb >> 8) & 0xFF) * fade);
        int b = Math.round((rgb & 0xFF) * fade);
        return (r << 16) | (g << 8) | b;
    }

    private static void trailVertex(MatrixStack.Entry entry, VertexConsumer consumer, Vec3d pos, float u, float v,
                                    int rgb) {
        vertex(entry, consumer, (float) pos.x, (float) pos.y, (float) pos.z, u, v,
                (rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF);
    }

    private static void vertex(MatrixStack.Entry entry, VertexConsumer consumer, float x, float y, float z,
                               float u, float v, int r, int g, int b) {
        consumer.vertex(entry, x, y, z)
                .color(r, g, b, 255)
                .texture(u, v)
                .overlay(OverlayTexture.DEFAULT_UV)
                .light(FULL_BRIGHT)
                .normal(entry, 0.0F, 1.0F, 0.0F);
    }

    @Override
    public Identifier getTexture(OlegusArrowEntity arrow) {
        return TEXTURE;
    }

    /** Стрела светится сама и не темнеет в пещерах. */
    @Override
    protected int getBlockLight(OlegusArrowEntity arrow, BlockPos pos) {
        return 15;
    }
}
