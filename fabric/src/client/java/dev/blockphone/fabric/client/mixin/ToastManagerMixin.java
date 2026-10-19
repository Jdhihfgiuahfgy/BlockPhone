package dev.blockphone.fabric.client.mixin;

import dev.blockphone.fabric.client.camera.CameraMode;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.toasts.ToastManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Всплывающие подсказки игры (достижения, обучение) не закрывают видоискатель. */
@Mixin(ToastManager.class)
public abstract class ToastManagerMixin {

    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void blockphone$hideInCamera(GuiGraphics guiGraphics, CallbackInfo ci) {
        if (CameraMode.active()) {
            ci.cancel();
        }
    }
}
