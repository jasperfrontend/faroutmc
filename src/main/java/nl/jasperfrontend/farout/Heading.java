package nl.jasperfrontend.farout;

import net.minecraft.core.BlockPos;

import java.util.Locale;

public enum Heading {
	NORTH("North", 180f),
	EAST("East", -90f),
	SOUTH("South", 0f),
	WEST("West", 90f);

	public final String label;
	/** Yaw that faces this heading, so the player starts looking the right way. */
	public final float yaw;

	Heading(String label, float yaw) {
		this.label = label;
		this.yaw = yaw;
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
