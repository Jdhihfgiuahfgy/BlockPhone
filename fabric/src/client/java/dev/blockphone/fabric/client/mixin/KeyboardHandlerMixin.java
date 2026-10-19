package dev.blockphone.fabric.client.mixin;

import dev.blockphone.fabric.client.camera.CameraMode;
import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.KeyEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Клавиши видоискателя (Esc, стрелки, H, G, R) перехватываются до игры. */
@Mixin(KeyboardHandler.class)
public abstract class KeyboardHandlerMixin {

    @Inject(method = "keyPress", at = @At("HEAD"), cancellable = true)
    private void blockphone$cameraKeys(long window, int action, KeyEvent event, CallbackInfo ci) {
        Minecraft mc = Minecraft.getInstance();
        if (window == mc.getWindow().handle() && mc.screen == null && CameraMode.onKey(action, event)) {
            ci.cancel();
        }
    }
}
