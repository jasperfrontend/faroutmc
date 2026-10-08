package nl.jasperfrontend.farout.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;
import net.minecraft.world.phys.Vec3;
import nl.jasperfrontend.farout.Farout;
import nl.jasperfrontend.farout.client.mixin.AbstractBoatAccessor;
import org.lwjgl.glfw.GLFW;

/**
 * A key (I by default, rebindable under Options › Controls › Key Binds › Farout) that snaps the boat you're
 * steering to the nearest of north, east, south or west. The steering client moves the boat and tells the
 * server where it went, so changing it here is all it takes, like any other boat movement.
 */
final class BoatStraightener {
	private static final KeyMapping.Category CATEGORY =
		KeyMapping.Category.register(Identifier.fromNamespaceAndPath(Farout.MOD_ID, Farout.MOD_ID));

	private BoatStraightener() {
	}

	static void register() {
		KeyMapping key = KeyMappingHelper.registerKeyMapping(new KeyMapping(
			"key.farout.straighten_boat", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_I, CATEGORY));
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			while (key.consumeClick()) {
				straighten(client);
			}
		});
	}

	private static void straighten(Minecraft client) {
		LocalPlayer player = client.player;
		if (player == null || !(player.getVehicle() instanceof AbstractBoat boat)
			|| boat.getControllingPassenger() != player) {
			return;
		}

		float yaw = Mth.wrapDegrees(boat.getYRot());
		float straight = Math.round(yaw / 90f) * 90f;
		float turn = straight - yaw;

		boat.setYRot(straight);
		boat.yRotO = straight;
		// Vanilla keeps turning a boat for a moment after you let go; without this it drifts off again.
		((AbstractBoatAccessor) boat).farout$setDeltaRotation(0f);

		// Keep the speed, but only along the new heading, so the boat doesn't slide sideways.
		Vec3 motion = boat.getDeltaMovement();
		double forwardX = -Mth.sin(straight * Mth.DEG_TO_RAD);
		double forwardZ = Mth.cos(straight * Mth.DEG_TO_RAD);
		double speed = motion.x * forwardX + motion.z * forwardZ;
		boat.setDeltaMovement(forwardX * speed, motion.y, forwardZ * speed);

		// Turn your view with the boat so you're still looking the same way relative to it.
		player.setYRot(player.getYRot() + turn);
		player.yRotO = player.getYRot();
		player.setYHeadRot(player.getYRot());

		player.playSound(SoundEvents.BOAT_PADDLE_WATER, 0.6f, 1.4f);
	}
}
