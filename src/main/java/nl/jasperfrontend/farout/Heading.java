package nl.jasperfrontend.farout;

import net.minecraft.core.BlockPos;

import java.util.Locale;

public enum Heading {
	NORTH("North", 180f, 0, -1),
	EAST("East", -90f, 1, 0),
	SOUTH("South", 0f, 0, 1),
	WEST("West", 90f, -1, 0);

	public final String label;
	/** Yaw that faces this heading, so the player starts looking the right way. */
	public final float yaw;
	/** One block forward along this heading. */
	public final int stepX;
	public final int stepZ;

	Heading(String label, float yaw, int stepX, int stepZ) {
		this.label = label;
		this.yaw = yaw;
		this.stepX = stepX;
		this.stepZ = stepZ;
	}

	/** The sideways coordinate: x when heading north or south, z when heading east or west. */
	public double lateral(double x, double z) {
		return stepX == 0 ? x : z;
	}

	public int lateral(BlockPos pos) {
		return stepX == 0 ? pos.getX() : pos.getZ();
	}

	/** Blocks travelled along this heading's axis from origin; sideways movement is ignored. */
	public int progress(BlockPos origin, BlockPos current) {
		return switch (this) {
			case NORTH -> origin.getZ() - current.getZ();
			case SOUTH -> current.getZ() - origin.getZ();
			case EAST -> current.getX() - origin.getX();
			case WEST -> origin.getX() - current.getX();
		};
	}

	public String id() {
		return name().toLowerCase(Locale.ROOT);
	}
}
