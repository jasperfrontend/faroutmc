package nl.jasperfrontend.farout;

import net.minecraft.core.BlockPos;

final class Run {
	final Heading heading;
	final BlockPos origin;
	long ticks;
	int nextSplit = Farout.SPLIT_INTERVAL;

	Run(Heading heading, BlockPos origin) {
		this.heading = heading;
		this.origin = origin;
	}
}
