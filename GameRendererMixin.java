package com.susano.mixin;

import com.susano.client.SusanooClient;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Elargit le champ de vision a chaque forme du Susanoo (sans toucher au reglage du joueur). */
@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
    @Inject(method = "getFov", at = @At("RETURN"), cancellable = true)
    private void susanoo$fov(Camera camera, float tickDelta, boolean changingFov, CallbackInfoReturnable<Double> cir) {
        if (!changingFov) {
            return;
        }
        float bonus = SusanooClient.fovBonus(tickDelta);
        if (bonus > 0.001f) {
            cir.setReturnValue(Math.min(170.0, cir.getReturnValue() + bonus));
        }
    }
}
