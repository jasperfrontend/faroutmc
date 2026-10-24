package nl.jasperfrontend.farout.client;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ContainerObjectSelectionList;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.layouts.HeaderAndFooterLayout;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import nl.jasperfrontend.farout.Farout;
import nl.jasperfrontend.farout.ResultsLog;
import nl.jasperfrontend.farout.Settings;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Farout's settings, saved seeds and finished runs, from Mod Menu's Configure button or /farout menu. The
 * settings are the same file /farout settings writes, so they count for worlds played on this computer, and
 * the finished runs are results.csv, from those same worlds.
 */
public class FaroutSettingsScreen extends Screen {
	private static final int ROW_HEIGHT = 24;
	private static final int ROW_WIDTH = 310;
	private static final int FIELD_WIDTH = 80;
	private static final int BUTTON_WIDTH = 50;
	private static final int TP_BUTTON_WIDTH = 60;
	private static final int TEXT = 0xFFFFFFFF;
	private static final int MUTED = 0xFFA0A0A0;
	private static final int INVALID = 0xFFFF5555;

	private final Screen parent;
	private final HeaderAndFooterLayout layout = new HeaderAndFooterLayout(this);
	private final Map<String, EditBox> fields = new LinkedHashMap<>();
	private Rows rows;
	private Button doneButton;

	public FaroutSettingsScreen(Screen parent) {
		super(Component.literal("Farout"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		layout.addTitleHeader(title, font);
		rows = layout.addToContents(new Rows());
		LinearLayout buttons = layout.addToFooter(LinearLayout.horizontal().spacing(8));
		doneButton = buttons.addChild(Button.builder(CommonComponents.GUI_DONE, button -> saveAndClose()).build());
		buttons.addChild(Button.builder(CommonComponents.GUI_CANCEL, button -> onClose()).build());
		layout.visitWidgets(this::addRenderableWidget);
		repositionElements();
		validate();
	}

	@Override
	protected void repositionElements() {
		layout.arrangeElements();
		if (rows != null) {
			rows.updateSize(width, layout);
		}
	}

	@Override
	public void onClose() {
		minecraft.setScreen(parent);
	}

	/** The value typed for a setting, or null if it isn't a whole number in range. */
	private static Integer parse(String name, String text) {
		try {
			int value = Integer.parseInt(text.trim());
			return value >= Settings.min(name) && value <= Settings.max(name) ? value : null;
		} catch (NumberFormatException e) {
			return null;
		}
	}

	private void validate() {
		boolean valid = true;
		for (Map.Entry<String, EditBox> field : fields.entrySet()) {
			boolean ok = parse(field.getKey(), field.getValue().getValue()) != null;
			field.getValue().setTextColor(ok ? 0xFFE0E0E0 : INVALID);
			valid &= ok;
		}
		if (doneButton != null) {
			doneButton.active = valid;
		}
	}

	private void saveAndClose() {
		Settings settings = Settings.load();
		boolean changed = false;
		for (Map.Entry<String, EditBox> field : fields.entrySet()) {
			int value = parse(field.getKey(), field.getValue().getValue());
			changed |= value != settings.get(field.getKey());
			settings = settings.with(field.getKey(), value);
		}
		if (changed) {
			try {
				settings.save();
			} catch (IOException e) {
				Farout.LOGGER.error("Could not write settings", e);
			}
		}
		onClose();
	}

	private class Rows extends ContainerObjectSelectionList<Row> {
		Rows() {
			super(FaroutSettingsScreen.this.minecraft, FaroutSettingsScreen.this.width, layout.getContentHeight(),
				layout.getHeaderHeight(), ROW_HEIGHT);
			addEntry(new TextRow(Component.literal("Settings").withStyle(ChatFormatting.BOLD), TEXT));
			Settings settings = Settings.load();
			for (String name : Settings.NAMES) {
				addEntry(new SettingRow(name, settings.get(name)));
			}
			addEntry(new TextRow(Component.literal("They apply from your next run, in worlds on this computer."), MUTED));
			refreshLists();
		}

		/** Rebuilds everything below the settings: saved seeds, then finished runs, newest first. */
		void refreshLists() {
			removeEntries(List.copyOf(children().subList(Settings.NAMES.length + 2, children().size())));
			List<SavedSeeds.Entry> seeds = SavedSeeds.load();
			addEntry(new TextRow(Component.literal("Saved seeds (" + seeds.size() + ")").withStyle(ChatFormatting.BOLD), TEXT));
			if (seeds.isEmpty()) {
				addEntry(new TextRow(Component.literal("None yet. When a run ends, Farout asks if you want its seed."), MUTED));
			}
			for (int i = seeds.size() - 1; i >= 0; i--) {
				addEntry(new SeedRow(seeds.get(i)));
			}

			List<ResultsLog.Result> results = ResultsLog.load();
			addEntry(new TextRow(Component.literal("Finished runs (" + results.size() + ")").withStyle(ChatFormatting.BOLD), TEXT));
			if (results.isEmpty()) {
				addEntry(new TextRow(Component.literal("None yet. Every run you finish is listed here."), MUTED));
			}
			for (int i = results.size() - 1; i >= 0; i--) {
				addEntry(new ResultRow(results.get(i)));
			}
		}

		@Override
		public int getRowWidth() {
			return ROW_WIDTH;
		}
	}

	private abstract static class Row extends ContainerObjectSelectionList.Entry<Row> {
	}

	private class TextRow extends Row {
		private final Component text;
		private final int color;

		TextRow(Component text, int color) {
			this.text = text;
			this.color = color;
		}

		@Override
		public void extractContent(GuiGraphicsExtractor graphics, int mouseX, int mouseY, boolean hovered, float a) {
			graphics.text(font, text, getContentX(), getContentYMiddle() - font.lineHeight / 2, color, false);
		}

		@Override
		public List<? extends GuiEventListener> children() {
			return List.of();
		}

		@Override
		public List<? extends NarratableEntry> narratables() {
			return List.of();
		}
	}

	private class SettingRow extends Row {
		private final Component label;
		private final EditBox field;

		SettingRow(String name, int value) {
			label = Component.literal(name);
			field = new EditBox(font, FIELD_WIDTH, 20, label);
			field.setMaxLength(7);
			field.setValue(Integer.toString(value));
			field.setResponder(text -> validate());
			field.setTooltip(Tooltip.create(Component.literal(Settings.min(name) + " to " + Settings.max(name)
				+ ", default " + Settings.defaultValue(name))));
			fields.put(name, field);
		}

		@Override
		public void extractContent(GuiGraphicsExtractor graphics, int mouseX, int mouseY, boolean hovered, float a) {
			graphics.text(font, label, getContentX(), getContentYMiddle() - font.lineHeight / 2, TEXT, false);
			field.setPosition(getContentRight() - FIELD_WIDTH, getContentY());
			field.extractRenderState(graphics, mouseX, mouseY, a);
		}

		@Override
		public List<? extends GuiEventListener> children() {
			return List.of(field);
		}

		@Override
		public List<? extends NarratableEntry> narratables() {
			return List.of(field);
		}
	}

	private class SeedRow extends Row {
		private final SavedSeeds.Entry entry;
		private final Component directions;
		private final Button copy;
		private final Button delete;
		/** Deleting takes a second click, so a seed isn't lost to a stray one. */
		private boolean armed;

		SeedRow(SavedSeeds.Entry entry) {
			this.entry = entry;
			directions = Component.literal(String.join(", ", entry.directions().stream().map(SeedPrompt::label).toList()));
			copy = Button.builder(Component.literal("Copy"), button -> {
				minecraft.keyboardHandler.setClipboard(entry.seed());
				button.setMessage(Component.literal("Copied"));
			}).size(BUTTON_WIDTH, 20).tooltip(Tooltip.create(Component.literal("Copy the seed, to paste into Create New World"))).build();
			delete = Button.builder(Component.literal("Delete"), button -> {
				if (!armed) {
					armed = true;
					button.setMessage(Component.literal("Sure?").withStyle(ChatFormatting.RED));
					return;
				}
				try {
					SavedSeeds.remove(entry.seed());
				} catch (IOException e) {
					Farout.LOGGER.error("Could not write saved seeds", e);
				}
				rows.refreshLists();
			}).size(BUTTON_WIDTH, 20).build();
		}

		@Override
		public void extractContent(GuiGraphicsExtractor graphics, int mouseX, int mouseY, boolean hovered, float a) {
			int x = getContentX();
			int y = getContentY();
			graphics.text(font, Component.literal(entry.seed()), x, y + 1, TEXT, false);
			graphics.text(font, directions, x, y + 1 + font.lineHeight + 1, MUTED, false);
			delete.setPosition(getContentRight() - BUTTON_WIDTH, y);
			copy.setPosition(delete.getX() - 4 - BUTTON_WIDTH, y);
			copy.extractRenderState(graphics, mouseX, mouseY, a);
			delete.extractRenderState(graphics, mouseX, mouseY, a);
		}

		@Override
		public List<? extends GuiEventListener> children() {
			return List.of(copy, delete);
		}

		@Override
		public List<? extends NarratableEntry> narratables() {
			return List.of(copy, delete);
		}
	}

	/** A finished run, with a button to copy a /tp to where it started; runs from before 1.0.24 have no start. */
	private class ResultRow extends Row {
		private final Component run;
		private final Component start;
		private final Button copy;

		ResultRow(ResultsLog.Result result) {
			String day = result.date().length() >= 10 ? result.date().substring(0, 10) : result.date();
			run = Component.literal(SeedPrompt.label(result.direction()) + " · " + result.time() + " · " + day
				+ (result.bonusChest() ? " · bonus chest" : ""));
			start = Component.literal((result.start().isEmpty() ? "Start not recorded" : result.start())
				+ " · seed " + result.seed());
			if (result.start().isEmpty()) {
				copy = null;
				return;
			}
			String tp = "/tp " + result.start();
			copy = Button.builder(Component.literal("Copy /tp"), button -> {
				minecraft.keyboardHandler.setClipboard(tp);
				button.setMessage(Component.literal("Copied"));
			}).size(TP_BUTTON_WIDTH, 20).tooltip(Tooltip.create(Component.literal("Copy " + tp + ", to paste into chat. "
				+ "Then /farout here runs this course again. /tp needs cheats on."))).build();
		}

		@Override
		public void extractContent(GuiGraphicsExtractor graphics, int mouseX, int mouseY, boolean hovered, float a) {
			int x = getContentX();
			int y = getContentY();
			graphics.text(font, run, x, y + 1, TEXT, false);
			graphics.text(font, start, x, y + 1 + font.lineHeight + 1, MUTED, false);
			if (copy != null) {
				copy.setPosition(getContentRight() - TP_BUTTON_WIDTH, y);
				copy.extractRenderState(graphics, mouseX, mouseY, a);
			}
		}

		@Override
		public List<? extends GuiEventListener> children() {
			return copy == null ? List.of() : List.of(copy);
		}

		@Override
		public List<? extends NarratableEntry> narratables() {
			return copy == null ? List.of() : List.of(copy);
		}
	}
}
