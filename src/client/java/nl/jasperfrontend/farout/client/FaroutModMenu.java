package nl.jasperfrontend.farout.client;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

/** Mod Menu's Configure button. Only loaded when Mod Menu is installed. */
public class FaroutModMenu implements ModMenuApi {
	@Override
	public ConfigScreenFactory<?> getModConfigScreenFactory() {
		return FaroutSettingsScreen::new;
	}
}
