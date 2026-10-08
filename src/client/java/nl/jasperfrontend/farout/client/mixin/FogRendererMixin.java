package nl.jasperfrontend.farout.client.mixin;

import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.fog.FogData;
import net.minecraft.client.renderer.fog.FogRenderer;
import nl.jasperfrontend.farout.client.LaneFog;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Lets the lane thicken the fog after vanilla has worked out its own for this frame. */
@Mixin(FogRenderer.class)
public class FogRendererMixin {
	@Inject(method = "setupFog", at = @At("RETURN"))
	private void farout$laneFog(Camera camera, int renderDistance, DeltaTracker deltaTracker, float darkening,
		ClientLevel level, CallbackInfoReturnable<FogData> cir) {
		LaneFog.apply(cir.getReturnValue(), camera, deltaTracker.getGameTimeDeltaPartialTick(false));
	}
}
