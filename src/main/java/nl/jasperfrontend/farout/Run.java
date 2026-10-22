package nl.jasperfrontend.farout;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

import java.util.ArrayList;
import java.util.List;

final class Run {
	final Heading heading;
	/** The run's own copy of the overworld, see {@link RunWorlds}. */
	final ServerLevel level;
	final BlockPos origin;
	/** The start area's centre, or the origin if there was none: where a /tp and /farout here run it again. */
	final BlockPos start;
	/** The start area's centre for a run from /farout here; null for one from spawn. */
	final BlockPos spot;
	final Lane lane;
	/** Split ticks of the personal best for this direction, seed and spot, or null if there is none yet. */
	final long[] pb;
	/** Split ticks of this run so far; the finish is added as the last entry. */
	final List<Long> splits = new ArrayList<>();
	long ticks;
	int nextSplit = Farout.SPLIT_INTERVAL;

	Run(Heading heading, ServerLevel level, BlockPos origin, BlockPos start, BlockPos spot, Lane lane, long[] pb) {
		this.heading = heading;
		this.level = level;
		this.origin = origin;
		this.start = start;
		this.spot = spot;
		this.lane = lane;
		this.pb = pb;
	}

	/** This run's time minus the personal best at the split just recorded, or null if there is nothing to compare. */
	Long deltaAtLastSplit() {
		int i = splits.size() - 1;
		return pb != null && i < pb.length ? splits.get(i) - pb[i] : null;
	}
}
