package dev.blockphone.fabric.client.compat;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.block.MovingBlockRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

/** Копия блока для «качания» колонок — версия для 26.x: {@code submitBlock} нет, блок рисуется как движущийся. */
public final class BlockPulse {
    public static void submit(PoseStack pose, SubmitNodeCollector queue, ClientLevel level, BlockPos pos, BlockState state) {
        MovingBlockRenderState s = new MovingBlockRenderState();
        s.randomSeedPos = pos;
        s.blockPos = pos;
        s.blockState = state;
        s.biome = level.getBiome(pos);
        s.cardinalLighting = level.cardinalLighting();
        s.lightEngine = level.getLightEngine();
        queue.submitMovingBlock(pose, s);
    }

    private BlockPulse() {
    }
}
