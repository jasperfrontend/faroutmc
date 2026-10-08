package nl.jasperfrontend.farout.client.mixin;

import net.minecraft.world.entity.vehicle.boat.AbstractBoat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Lets us zero the boat's turning momentum, which vanilla keeps in a private field. */
@Mixin(AbstractBoat.class)
public interface AbstractBoatAccessor {
	@Accessor("deltaRotation")
	void farout$setDeltaRotation(float deltaRotation);
}
