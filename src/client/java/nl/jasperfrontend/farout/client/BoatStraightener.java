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
 * A key (I by default, rebindable under Options › Controls › Key Binds › Farout) that turns the boat you're
 * steering to the nearest of north, east, south or west, easing in over a few ticks rather than snapping. The steering client moves the boat and tells the
 * server where it went, so changing it here is all it takes, like any other boat movement.
 */
final class BoatStraightener {
	private static final KeyMapping.Category CATEGORY =
		KeyMapping.Category.register(Identifier.fromNamespaceAndPath(Farout.MOD_ID, Farout.MOD_ID));
	/** How long the turn takes. */
	private static final int EASE_TICKS = 6;

	/** The boat being turned, or null when no turn is under way. */
	private static AbstractBoat turning;
	private static float fromYaw;
	private static float turn;
	private static int tick;

	private BoatStraightener() {
	}

	static void register() {
		KeyMapping key = KeyMappingHelper.registerKeyMapping(new KeyMapping(
			"key.farout.straighten_boat", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_I, CATEGORY));
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			while (key.consumeClick()) {
				start(client);
			}
			step(client);
		});
	}

	/** Aims at the nearest straight heading; pressing again mid-turn just re-aims from where the boat is. */
	private static void start(Minecraft client) {
		AbstractBoat boat = steeredBoat(client);
		if (boat == null) {
			return;
		}
		// Yaw isn't kept within ±180, so turn from the raw value: jumping to the wrapped one would spin the boat
		// a full circle on screen while rendering interpolates.
		float yaw = Mth.wrapDegrees(boat.getYRot());
		turning = boat;
		fromYaw = boat.getYRot();
		turn = Math.round(yaw / 90f) * 90f - yaw;
		tick = 0;
		client.player.playSound(SoundEvents.BOAT_PADDLE_WATER, 0.6f, 1.4f);
	}

	/** One tick of the turn, after the boat's own tick, so rendering interpolates smoothly up to it. */
	private static void step(Minecraft client) {
		if (turning == null) {
			return;
		}
		AbstractBoat boat = steeredBoat(client);
		if (boat != turning) {
			turning = null;
			return;
		}

		tick++;
		float t = (float) tick / EASE_TICKS;
		float eased = 1f - (1f - t) * (1f - t) * (1f - t); // ease-out cubic: quick start, gentle finish
		float yaw = fromYaw + turn * eased;
		float change = yaw - boat.getYRot();

		boat.setYRot(yaw);
		// Vanilla keeps turning a boat for a moment after you let go; without this it drifts off again.
		((AbstractBoatAccessor) boat).farout$setDeltaRotation(0f);

		// Keep the speed, but only along the new heading, so the boat carves round instead of sliding sideways.
		Vec3 motion = boat.getDeltaMovement();
		double forwardX = -Mth.sin(yaw * Mth.DEG_TO_RAD);
		double forwardZ = Mth.cos(yaw * Mth.DEG_TO_RAD);
		double speed = motion.x * forwardX + motion.z * forwardZ;
		boat.setDeltaMovement(forwardX * speed, motion.y, forwardZ * speed);

		// Turn your view with the boat so you're still looking the same way relative to it.
		LocalPlayer player = client.player;
		player.setYRot(player.getYRot() + change);
		player.setYHeadRot(player.getYRot());

		if (tick >= EASE_TICKS) {
			turning = null;
		}
	}

	/** The boat the player is steering, or null. */
	private static AbstractBoat steeredBoat(Minecraft client) {
		LocalPlayer player = client.player;
		if (player != null && player.getVehicle() instanceof AbstractBoat boat
			&& boat.getControllingPassenger() == player) {
			return boat;
		}
		return null;
	}
}
