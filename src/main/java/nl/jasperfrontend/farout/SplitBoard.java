package nl.jasperfrontend.farout;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.numbers.BlankFormat;
import net.minecraft.network.chat.numbers.FixedFormat;
import net.minecraft.network.protocol.game.ClientboundSetDisplayObjectivePacket;
import net.minecraft.network.protocol.game.ClientboundSetObjectivePacket;
import net.minecraft.network.protocol.game.ClientboundSetScorePacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.scores.criteria.ObjectiveCriteria;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Per-player sidebar listing the run's splits. It only exists on that player's client (sent as packets),
 * so the world's own scoreboard is never touched.
 */
final class SplitBoard {
	private static final String OBJECTIVE = "farout_splits";
	// Objective needs a Scoreboard to exist; this one is never registered or synced.
	private static final Scoreboard DETACHED = new Scoreboard();
	// The client throws on adding an objective it already has, so track who has one.
	private static final Set<UUID> SHOWN = new HashSet<>();

	private SplitBoard() {}

	static void show(ServerPlayer player, Heading heading) {
		clear(player);
		Objective objective = new Objective(DETACHED, OBJECTIVE, ObjectiveCriteria.DUMMY,
			Component.literal("Farout · " + heading.label).withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD),
			ObjectiveCriteria.RenderType.INTEGER, false, BlankFormat.INSTANCE);
		player.connection.send(new ClientboundSetObjectivePacket(objective, ClientboundSetObjectivePacket.METHOD_ADD));
		player.connection.send(new ClientboundSetDisplayObjectivePacket(DisplaySlot.SIDEBAR, objective));
		SHOWN.add(player.getUUID());
	}

	static void split(ServerPlayer player, int blocks, long ticks, boolean finish) {
		if (!SHOWN.contains(player.getUUID())) {
			return;
		}
		ChatFormatting color = finish ? ChatFormatting.GOLD : ChatFormatting.GRAY;
		// The sidebar sorts by score, highest first; negative distance keeps the first split on top.
		player.connection.send(new ClientboundSetScorePacket(Format.blocks(blocks), OBJECTIVE, -blocks,
			Optional.of(Component.literal(Format.blocks(blocks)).withStyle(color)),
			Optional.of(new FixedFormat(Component.literal(Format.time(ticks)).withStyle(finish ? ChatFormatting.GOLD : ChatFormatting.WHITE)))));
	}

	/** Removes the sidebar if the player has one; returns whether it did. */
	static boolean clear(ServerPlayer player) {
		if (!SHOWN.remove(player.getUUID())) {
			return false;
		}
		Objective objective = new Objective(DETACHED, OBJECTIVE, ObjectiveCriteria.DUMMY, Component.empty(),
			ObjectiveCriteria.RenderType.INTEGER, false, BlankFormat.INSTANCE);
		player.connection.send(new ClientboundSetObjectivePacket(objective, ClientboundSetObjectivePacket.METHOD_REMOVE));
		return true;
	}

	/** Forgets a player without sending anything (they disconnected, their client dropped it already). */
	static void forget(UUID player) {
		SHOWN.remove(player);
	}

	static void forgetAll() {
		SHOWN.clear();
	}
}
