package nl.jasperfrontend.farout;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The power-up recipes, floating in the four corners of the start area: a 3x3 grid of items per recipe
 * with its name and effect above. Slots that take "any" of something cycle through every item that fits.
 */
final class RecipeBoard {
	/** Prefix of the entity tag on a cycling slot; the rest is the item tag it cycles through. */
	private static final String ANY_TAG = "farout_any=";

	private static final double CORNER = 3.3;
	private static final double SPACING = 0.5;
	private static final double TOP_ROW = 2.5;

	/** One recipe panel: the corner it's in, and its ring, centre and bottom items. */
	private record Panel(Power power, int sx, int sz, String note, Slot ring, Slot centre, Slot bottom) {
	}

	/** An item to show, and the item tag to cycle through if the recipe takes any of a kind. */
	private record Slot(String item, String anyOf) {
		static Slot of(String item) {
			return new Slot(item, null);
		}

		static Slot any(String item, String tag) {
			return new Slot(item, tag);
		}
	}

	private static final List<Panel> PANELS = List.of(
		new Panel(Power.MOUNTAIN_HOP, 1, -1, "Any boots. Coal, not charcoal.",
			Slot.of("coal"), Slot.any("leather_boots", "foot_armor"), Slot.of("feather")),
		new Panel(Power.POWER_DRILL, 1, 1, "Any pickaxe, any buttons.",
			Slot.any("oak_button", "buttons"), Slot.any("wooden_pickaxe", "pickaxes"), Slot.of("torch")),
		new Panel(Power.CANT_HURT_ME, -1, 1, "Any logs, any carpet.",
			Slot.any("oak_log", "logs"), Slot.of("shield"), Slot.any("white_carpet", "wool_carpets")),
		new Panel(Power.MORNING_COFFEE, -1, -1, "Only within a minute of waking up.",
			Slot.any("oak_button", "buttons"), Slot.any("red_bed", "beds"), Slot.of("torch")));

	private RecipeBoard() {
	}

	/** Summons all four panels around the hub centre; call after the old hub entities are gone. */
	static void build(Commands commands, CommandSourceStack source, BlockPos c) {
		for (Panel panel : PANELS) {
			double px = c.getX() + 0.5 + panel.sx * CORNER;
			double pz = c.getZ() + 0.5 + panel.sz * CORNER;
			// Looking from the centre towards the corner, "right" is the look direction turned clockwise.
			double rightX = -panel.sz * Math.sqrt(0.5);
			double rightZ = panel.sx * Math.sqrt(0.5);

			Slot[] grid = {
				panel.ring, null, panel.ring,
				panel.ring, panel.centre, panel.ring,
				panel.ring, panel.bottom, panel.ring,
			};
			for (int i = 0; i < grid.length; i++) {
				if (grid[i] == null) {
					continue;
				}
				int col = i % 3 - 1;
				int row = i / 3;
				item(commands, source, grid[i],
					px + rightX * col * SPACING, c.getY() + TOP_ROW - row * SPACING, pz + rightZ * col * SPACING);
			}

			commands.performPrefixedCommand(source, String.format(Locale.ROOT,
				"summon minecraft:text_display %.2f %.2f %.2f {Tags:[\"%s\"],billboard:\"center\",background:0,"
					+ "text:{text:\"%s\",color:\"%s\",bold:true,extra:["
					+ "{text:\"\\n%s\",color:\"white\",bold:false},"
					+ "{text:\"\\n%s\",color:\"gray\",bold:false}]},"
					+ "transformation:{left_rotation:[0f,0f,0f,1f],right_rotation:[0f,0f,0f,1f],"
					+ "translation:[0f,0f,0f],scale:[0.7f,0.7f,0.7f]}}",
				px, c.getY() + TOP_ROW + 0.35, pz, SpawnHub.HUB_TAG, panel.power.label, panel.power.colour.getName(),
				panel.power.effect + " for 30s", panel.note));
		}
	}

	private static void item(Commands commands, CommandSourceStack source, Slot slot, double x, double y, double z) {
		String tags = "\"" + SpawnHub.HUB_TAG + "\"" + (slot.anyOf == null ? "" : ",\"" + ANY_TAG + slot.anyOf + "\"");
		commands.performPrefixedCommand(source, String.format(Locale.ROOT,
			"summon minecraft:item_display %.3f %.3f %.3f {Tags:[%s],item:{id:\"minecraft:%s\"},"
				+ "billboard:\"center\",item_display:\"gui\","
				+ "transformation:{left_rotation:[0f,0f,0f,1f],right_rotation:[0f,0f,0f,1f],"
				+ "translation:[0f,0f,0f],scale:[0.4f,0.4f,0.4f]}}",
			x, y, z, tags, slot.item));
	}

	/** Moves every "any" slot on to the next item that fits; step is how many times it has moved so far. */
	static void cycle(ServerLevel level, BlockPos c, long step) {
		AABB area = new AABB(c).inflate(6);
		for (Display.ItemDisplay display : level.getEntities(EntityType.ITEM_DISPLAY, area, e -> true)) {
			for (String tag : display.entityTags()) {
				if (!tag.startsWith(ANY_TAG)) {
					continue;
				}
				TagKey<Item> key = TagKey.create(Registries.ITEM,
					Identifier.withDefaultNamespace(tag.substring(ANY_TAG.length())));
				List<Holder<Item>> items = new ArrayList<>();
				BuiltInRegistries.ITEM.getTagOrEmpty(key).forEach(items::add);
				if (!items.isEmpty()) {
					display.setItemStack(new ItemStack(items.get((int) (step % items.size()))));
				}
			}
		}
	}
}
