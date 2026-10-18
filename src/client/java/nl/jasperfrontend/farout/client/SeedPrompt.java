package nl.jasperfrontend.farout.client;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.network.chat.Component;
import nl.jasperfrontend.farout.Farout;
import nl.jasperfrontend.farout.RunWorlds;
import nl.jasperfrontend.farout.SeedOfferPayload;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * After a run, asks whether to keep its seed. It waits until nothing else is on screen and the player is
 * out of the run's world, so it never covers the death screen or the finish. A seed that's already saved
 * just gets the new direction added, without asking.
 */
final class SeedPrompt {
	private static String pendingSeed;
	private static final Set<String> pendingDirections = new LinkedHashSet<>();

	private SeedPrompt() {
	}

	static void offer(SeedOfferPayload offer) {
		String seed = Long.toString(offer.seed());
		if (SavedSeeds.contains(seed)) {
			try {
				if (SavedSeeds.add(seed, offer.direction())) {
					chat(Component.literal("Added " + label(offer.direction()) + " to saved seed " + seed + ".")
						.withStyle(ChatFormatting.GRAY));
				}
			} catch (IOException e) {
				failed(e);
			}
			return;
		}
		if (!seed.equals(pendingSeed)) {
			pendingSeed = seed;
			pendingDirections.clear();
		}
		pendingDirections.add(offer.direction());
	}

	static void tick(Minecraft minecraft) {
		if (pendingSeed == null || minecraft.screen != null || minecraft.player == null || minecraft.level == null
			|| !minecraft.player.isAlive() || RunWorlds.isRunWorld(minecraft.level.dimension())) {
			return;
		}
		String seed = pendingSeed;
		List<String> directions = new ArrayList<>(pendingDirections);
		clear();

		String ran = String.join(", ", directions.stream().map(SeedPrompt::label).toList());
		minecraft.setScreen(new ConfirmScreen(save -> {
			minecraft.setScreen(null);
			if (!save) {
				return;
			}
			try {
				for (String direction : directions) {
					SavedSeeds.add(seed, direction);
				}
				chat(Component.literal("Saved seed " + seed + ". Find it with /farout menu.").withStyle(ChatFormatting.GRAY));
			} catch (IOException e) {
				failed(e);
			}
		}, Component.literal("Save this seed?"),
			Component.literal("Seed " + seed + ", run " + ran + ".\nThe run's world is gone, but you can keep the seed "
				+ "to run it again. Saved seeds are listed in Farout's settings."),
			Component.literal("Save seed"), Component.literal("Don't save")));
	}

	static void clear() {
		pendingSeed = null;
		pendingDirections.clear();
	}

	/** "north" to "North". */
	static String label(String direction) {
		return direction.isEmpty() ? direction
			: direction.substring(0, 1).toUpperCase(Locale.ROOT) + direction.substring(1);
	}

	private static void chat(Component message) {
		Minecraft.getInstance().gui.getChat().addClientSystemMessage(message);
	}

	private static void failed(IOException e) {
		Farout.LOGGER.error("Could not write saved seeds", e);
		chat(Component.literal("Could not save the seed: " + e.getMessage()).withStyle(ChatFormatting.RED));
	}
}
