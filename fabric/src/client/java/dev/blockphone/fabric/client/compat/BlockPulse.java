package dev.blockphone.fabric.client.compat;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Нарисовать блок (копию блока-колонки для «качания» от басов) в текущей матрице, от (0, 0, 0) до (1, 1, 1). Переходник:
 * в 1.21.x — {@code submitBlock} со светом соседей, в 26.x — свой вариант (fabric-26.1/src) через {@code submitMovingBlock}.
 */
public final class BlockPulse {
    public static void submit(PoseStack pose, SubmitNodeCollector queue, ClientLevel level, BlockPos pos, BlockState state) {
        int light = 0;
        for (Direction d : Direction.values()) {
            light = Math.max(light, LevelRenderer.getLightColor(level, pos.relative(d)));
        }
        queue.submitBlock(pose, state, light, OverlayTexture.NO_OVERLAY, 0);
    }

    private BlockPulse() {
    }
}
