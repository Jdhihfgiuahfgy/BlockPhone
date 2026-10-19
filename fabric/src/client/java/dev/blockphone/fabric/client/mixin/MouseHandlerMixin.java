package dev.blockphone.fabric.client.mixin;

import dev.blockphone.fabric.client.camera.CameraMode;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Камера: колесо мыши = зум (вместо смены слота), чувствительность мыши падает с зумом. */
@Mixin(MouseHandler.class)
public abstract class MouseHandlerMixin {

    @Inject(method = "onScroll", at = @At("HEAD"), cancellable = true)
    private void blockphone$cameraZoom(long window, double horizontal, double vertical, CallbackInfo ci) {
        Minecraft mc = Minecraft.getInstance();
        if (CameraMode.active() && mc.screen == null && window == mc.getWindow().handle()) {
            if (vertical != 0) {
                CameraMode.onScroll(Math.signum(vertical));
            }
            ci.cancel();
        }
    }

    @ModifyArg(method = "turnPlayer", index = 0,
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;turn(DD)V"))
    private double blockphone$zoomSensitivityX(double yaw) {
        return yaw / CameraMode.fovDivisor();
    }

    @ModifyArg(method = "turnPlayer", index = 1,
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;turn(DD)V"))
    private double blockphone$zoomSensitivityY(double pitch) {
        return pitch / CameraMode.fovDivisor();
    }
}
