package dev.blockphone.fabric.client.mixin;

import dev.blockphone.fabric.client.camera.CameraMode;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * В режиме камеры игровые действия по клавишам (атака, использование, инвентарь, хотбар, чат...) отключены:
 * ЛКМ/ПКМ забирает себе камера. Движение и обзор работают как обычно.
 */
@Mixin(Minecraft.class)
public abstract class MinecraftMixin {

    @Inject(method = "handleKeybinds", at = @At("HEAD"), cancellable = true)
    private void blockphone$cameraBlocksKeybinds(CallbackInfo ci) {
        if (CameraMode.active()) {
            ci.cancel();
        }
    }
}
