package nl.jasperfrontend.farout.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import nl.jasperfrontend.farout.Farout;
import nl.jasperfrontend.farout.RunStatePayload;

public class FaroutClient implements ClientModInitializer {
	// Vanilla draws the action bar centred on guiHeight - 68, text starting 4px above that.
	private static final int ACTION_BAR_Y = 68;
	private static final int LINE_HEIGHT = 10;

	private static boolean runActive;

	@Override
	public void onInitializeClient() {
		ClientPlayNetworking.registerGlobalReceiver(RunStatePayload.TYPE,
			(payload, context) -> runActive = payload.active());
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> runActive = false);

		HudElementRegistry.attachElementAfter(VanillaHudElements.OVERLAY_MESSAGE,
			Identifier.fromNamespaceAndPath(Farout.MOD_ID, "facing"),
			(graphics, deltaTracker) -> {
				Minecraft minecraft = Minecraft.getInstance();
				if (!runActive || minecraft.player == null) {
					return;
				}

				Direction facing = Direction.fromYRot(minecraft.player.getYRot());
				String name = facing.getName();
				Component text = Component.literal("Facing " + Character.toUpperCase(name.charAt(0)) + name.substring(1));

				Font font = minecraft.font;
				int width = font.width(text);
				int x = graphics.guiWidth() / 2 - width / 2;
				int y = graphics.guiHeight() - ACTION_BAR_Y - 4 + LINE_HEIGHT;
				graphics.textWithBackdrop(font, text, x, y, width, 0xFFFFFFFF);
			});
	}
}
