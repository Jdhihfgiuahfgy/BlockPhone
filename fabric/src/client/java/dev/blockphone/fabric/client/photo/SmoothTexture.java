package dev.blockphone.fabric.client.photo;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import net.minecraft.client.renderer.texture.DynamicTexture;

/**
 * Текстура с плавным масштабированием (фото при уменьшении не «рябят»). Версия для 1.21.11; для 1.21.10 — свой файл
 * в модуле fabric-1.21.10.
 */
public final class SmoothTexture extends DynamicTexture {
    public SmoothTexture(String label, NativeImage image) {
        super(() -> label, image);
        this.sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
    }
}
