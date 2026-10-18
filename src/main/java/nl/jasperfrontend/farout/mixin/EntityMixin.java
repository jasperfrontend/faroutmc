package nl.jasperfrontend.farout.mixin;

import net.minecraft.world.entity.Entity;
import nl.jasperfrontend.farout.RunWorlds;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Portals don't work in a run's world: they'd lead into the player's real Nether or End, with run items
 * and mobs in tow. Every portal, gateway and thrown pearl asks this before letting anything through.
 */
@Mixin(Entity.class)
abstract class EntityMixin {
	@Inject(method = "canUsePortal", at = @At("HEAD"), cancellable = true)
	private void farout$noPortalsInRunWorlds(boolean ignorePassenger, CallbackInfoReturnable<Boolean> cir) {
		if (RunWorlds.isRunWorld(((Entity) (Object) this).level().dimension())) {
			cir.setReturnValue(false);
		}
	}
}
