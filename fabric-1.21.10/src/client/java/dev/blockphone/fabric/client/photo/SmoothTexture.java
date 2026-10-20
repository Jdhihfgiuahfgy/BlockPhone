package dev.blockphone.fabric.client.photo;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.renderer.texture.DynamicTexture;

/** Текстура с плавным масштабированием — версия для 1.21.10. */
public final class SmoothTexture extends DynamicTexture {
    public SmoothTexture(String label, NativeImage image) {
        super(() -> label, image);
        setFilter(true, false);
    }
}
