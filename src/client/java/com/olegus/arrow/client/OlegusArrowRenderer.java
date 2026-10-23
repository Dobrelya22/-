package com.olegus.arrow.client;

import com.olegus.arrow.OlegusArrowMod;
import com.olegus.arrow.entity.OlegusArrowEntity;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.RotationAxis;

/** Модель как у ванильной стрелы (крестовина из четырёх плоскостей), но своя текстура и свечение. */
public class OlegusArrowRenderer extends EntityRenderer<OlegusArrowEntity> {
    private static final Identifier TEXTURE = OlegusArrowMod.id("textures/entity/olegus_arrow.png");

    public OlegusArrowRenderer(EntityRendererFactory.Context context) {
        super(context);
        this.shadowRadius = 0.0F;
    }

    @Override
    public void render(OlegusArrowEntity arrow, float yaw, float tickDelta, MatrixStack matrices,
                       VertexConsumerProvider vertexConsumers, int light) {
        matrices.push();
        matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(
                MathHelper.lerpAngleDegrees(tickDelta, arrow.prevYaw, arrow.getYaw()) - 90.0F));
        matrices.multiply(RotationAxis.POSITIVE_Z.rotationDegrees(
                MathHelper.lerp(tickDelta, arrow.prevPitch, arrow.getPitch())));
        matrices.multiply(RotationAxis.POSITIVE_X.rotationDegrees(45.0F));
        matrices.scale(0.05625F, 0.05625F, 0.05625F);
        matrices.translate(-4.0F, 0.0F, 0.0F);

        VertexConsumer consumer = vertexConsumers.getBuffer(RenderLayer.getEntityCutout(TEXTURE));
        MatrixStack.Entry entry = matrices.peek();
        // Торец с оперением.
        vertex(entry, consumer, -7, -2, -2, 0.0F, 0.15625F, -1, 0, 0, light);
        vertex(entry, consumer, -7, -2, 2, 0.15625F, 0.15625F, -1, 0, 0, light);
        vertex(entry, consumer, -7, 2, 2, 0.15625F, 0.3125F, -1, 0, 0, light);
        vertex(entry, consumer, -7, 2, -2, 0.0F, 0.3125F, -1, 0, 0, light);
        vertex(entry, consumer, -7, 2, -2, 0.0F, 0.15625F, 1, 0, 0, light);
        vertex(entry, consumer, -7, 2, 2, 0.15625F, 0.15625F, 1, 0, 0, light);
        vertex(entry, consumer, -7, -2, 2, 0.15625F, 0.3125F, 1, 0, 0, light);
        vertex(entry, consumer, -7, -2, -2, 0.0F, 0.3125F, 1, 0, 0, light);
        // Четыре продольные плоскости.
        for (int i = 0; i < 4; i++) {
            matrices.multiply(RotationAxis.POSITIVE_X.rotationDegrees(90.0F));
            vertex(entry, consumer, -8, -2, 0, 0.0F, 0.0F, 0, 1, 0, light);
            vertex(entry, consumer, 8, -2, 0, 0.5F, 0.0F, 0, 1, 0, light);
            vertex(entry, consumer, 8, 2, 0, 0.5F, 0.15625F, 0, 1, 0, light);
            vertex(entry, consumer, -8, 2, 0, 0.0F, 0.15625F, 0, 1, 0, light);
        }
        matrices.pop();
        super.render(arrow, yaw, tickDelta, matrices, vertexConsumers, light);
    }

    private static void vertex(MatrixStack.Entry entry, VertexConsumer consumer, int x, int y, int z, float u, float v,
                               int normalX, int normalZ, int normalY, int light) {
        consumer.vertex(entry, (float) x, (float) y, (float) z)
                .color(0xFFFFFFFF)
                .texture(u, v)
                .overlay(OverlayTexture.DEFAULT_UV)
                .light(light)
                .normal(entry, (float) normalX, (float) normalY, (float) normalZ);
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
