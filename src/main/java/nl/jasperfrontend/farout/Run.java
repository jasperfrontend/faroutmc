package nl.jasperfrontend.farout;

import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.List;

final class Run {
	final Heading heading;
	final BlockPos origin;
	final Lane lane;
	/** Split ticks of the personal best for this direction, or null if there is none yet. */
	final long[] pb;
	/** Split ticks of this run so far; the finish is added as the last entry. */
	final List<Long> splits = new ArrayList<>();
	long ticks;
	int nextSplit = Farout.SPLIT_INTERVAL;

	Run(Heading heading, BlockPos origin, Lane lane, long[] pb) {
		this.heading = heading;
		this.origin = origin;
		this.lane = lane;
		this.pb = pb;
	}

	/** This run's time minus the personal best at the split just recorded, or null if there is nothing to compare. */
	Long deltaAtLastSplit() {
		int i = splits.size() - 1;
		return pb != null && i < pb.length ? splits.get(i) - pb[i] : null;
	}
}
