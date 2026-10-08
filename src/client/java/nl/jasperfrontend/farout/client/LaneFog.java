package nl.jasperfrontend.farout.client;

import net.minecraft.client.Camera;
import net.minecraft.client.renderer.fog.FogData;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import nl.jasperfrontend.farout.LanePayload;

/**
 * Black fog that closes in the further you are outside the lane and lifts again as you head back.
 * Worked out every frame from your own position, so it follows you smoothly instead of in steps.
 */
public final class LaneFog {
	/** Blocks outside the lane at which the fog is as thick as it gets. */
	private static final float FULL_DISTANCE = 8f;
	/** How far you can still see at full strength; vanilla blindness is 5. */
	private static final float NEAREST_END = 4f;

	private static LanePayload lane = LanePayload.NONE;

	private LaneFog() {
	}

	static void set(LanePayload payload) {
		lane = payload;
	}

	/** Called by FogRendererMixin with the fog vanilla just worked out, before it goes to the GPU. */
	public static void apply(FogData fog, Camera camera, float partialTick) {
		Entity entity = camera.entity();
		if (!lane.active() || entity == null) {
			return;
		}

		Vec3 pos = entity.getPosition(partialTick);
		double lateral = lane.lateralIsX() ? pos.x : pos.z;
		double outside = Math.max(lane.low() - lateral, lateral - lane.high());
		if (outside <= 0) {
			return;
		}

		// Square root so the first step out already shows clearly, and it keeps closing in from there.
		float strength = Mth.sqrt(Math.min((float) outside / FULL_DISTANCE, 1f));
		// Eased in log space, so each step out takes a similar bite out of what you can see.
		float far = Math.max(fog.renderDistanceEnd, NEAREST_END);
		float end = (float) Math.exp(Mth.lerp(strength, Math.log(far), Math.log(NEAREST_END)));

		// Same proportions vanilla blindness uses for where the fog starts and how far the sky reaches.
		fog.environmentalStart = Math.min(fog.environmentalStart, end * 0.25f);
		fog.environmentalEnd = Math.min(fog.environmentalEnd, end);
		fog.skyEnd = Math.min(fog.skyEnd, end * 0.8f);
		fog.cloudEnd = Math.min(fog.cloudEnd, end * 0.8f);
		fog.color.set(fog.color.x * (1 - strength), fog.color.y * (1 - strength), fog.color.z * (1 - strength),
			fog.color.w);
	}
}
