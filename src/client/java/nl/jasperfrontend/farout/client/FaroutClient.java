package nl.jasperfrontend.farout.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import nl.jasperfrontend.farout.Farout;
import nl.jasperfrontend.farout.RunStatePayload;

public class FaroutClient implements ClientModInitializer {
	// Vanilla draws the action bar text at guiHeight - 72. The compass sits just above it,
	// clear of the held-item name that vanilla draws below the action bar.
	private static final int COMPASS_Y = 72 + 14;
	// Half the strip width in pixels; one pixel per degree shows 60° either side.
	private static final int HALF_WIDTH = 60;
	private static final String[] MARKS = {"N", "·", "E", "·", "S", "·", "W", "·"};

	private static boolean runActive;

	@Override
	public void onInitializeClient() {
		ClientPlayNetworking.registerGlobalReceiver(RunStatePayload.TYPE,
			(payload, context) -> runActive = payload.active());
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> runActive = false);

		HudElementRegistry.attachElementAfter(VanillaHudElements.OVERLAY_MESSAGE,
			Identifier.fromNamespaceAndPath(Farout.MOD_ID, "compass"),
			(graphics, deltaTracker) -> {
				Minecraft minecraft = Minecraft.getInstance();
				if (runActive && minecraft.player != null) {
					drawCompass(graphics, minecraft.font, minecraft.player.getYRot());
				}
			});
	}

	/** A scrolling strip of N/E/S/W with dots in between; the letter under the centre marker is where you face. */
	private static void drawCompass(GuiGraphicsExtractor graphics, Font font, float yaw) {
		int cx = graphics.guiWidth() / 2;
		int y = graphics.guiHeight() - COMPASS_Y;
		// Minecraft yaw: 0 = south, 90 = west. Compass bearing: 0 = north, 90 = east.
		float bearing = yaw + 180f;

		graphics.fill(cx - HALF_WIDTH - 4, y - 2, cx + HALF_WIDTH + 4, y + font.lineHeight, 0x80000000);
		for (int i = 0; i < MARKS.length; i++) {
			float offset = Mth.wrapDegrees(i * 45f - bearing);
			if (Math.abs(offset) > HALF_WIDTH) {
				continue;
			}
			String mark = MARKS[i];
			int color = Math.abs(offset) <= 22.5f && !mark.equals("·") ? 0xFFFFFF55 : 0xFFAAAAAA;
			graphics.text(font, mark, cx + Math.round(offset) - font.width(mark) / 2, y, color, true);
		}
		// Centre marker: a small tick above and below the strip.
		graphics.fill(cx, y - 4, cx + 1, y - 1, 0xFFFFFF55);
		graphics.fill(cx, y + font.lineHeight - 1, cx + 1, y + font.lineHeight + 2, 0xFFFFFF55);
	}
}
