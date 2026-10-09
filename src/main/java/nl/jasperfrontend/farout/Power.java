package nl.jasperfrontend.farout;

import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.util.StringRepresentable;
import net.minecraft.util.Unit;
import net.minecraft.world.BossEvent;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Locale;

/**
 * The crafted power-ups. Each one turns the tool in the middle of its recipe into a single-use item:
 * the 30 seconds start the first time you use it, and the item is gone when they're up.
 */
enum Power implements StringRepresentable {
	MOUNTAIN_HOP("Mountain Hop", "Boots", ChatFormatting.GREEN, BossEvent.BossBarColor.GREEN,
		"Jump Boost X, no fall damage", "Starts when you put them on"),
	POWER_DRILL("Power Drill", "Pickaxe", ChatFormatting.GOLD, BossEvent.BossBarColor.YELLOW,
		"Max Haste, mines 3x3", "Starts on the first block you mine"),
	CANT_HURT_ME("Can't Hurt Me", "Shield", ChatFormatting.AQUA, BossEvent.BossBarColor.BLUE,
		"Nothing hurts you, except falling", "Starts when you raise it"),
	MORNING_COFFEE("Morning Coffee", "Bed", ChatFormatting.LIGHT_PURPLE, BossEvent.BossBarColor.PINK,
		"Speed III", "Right-click to drink it");

	static final StringRepresentable.EnumCodec<Power> CODEC = StringRepresentable.fromEnum(Power::values);
	static final int DURATION_TICKS = 30 * 20;

	private static final String POWER_KEY = "farout_power";
	private static final String ACTIVE_KEY = "farout_active";

	final String label;
	/** What the crafted item is called after the power's name, e.g. "Mountain Hop Boots". */
	final String noun;
	final ChatFormatting colour;
	final BossEvent.BossBarColor barColour;
	final String effect;
	final String trigger;

	Power(String label, String noun, ChatFormatting colour, BossEvent.BossBarColor barColour, String effect, String trigger) {
		this.label = label;
		this.noun = noun;
		this.colour = colour;
		this.barColour = barColour;
		this.effect = effect;
		this.trigger = trigger;
	}

	@Override
	public String getSerializedName() {
		return name().toLowerCase(Locale.ROOT);
	}

	/** Turns a plain item into this power-up: name, glint, what it does, and the tag that marks it. */
	ItemStack decorate(ItemStack stack) {
		stack.setCount(1);
		stack.set(DataComponents.CUSTOM_NAME, Component.literal(label + " " + noun)
			.withStyle(colour, ChatFormatting.BOLD).withStyle(s -> s.withItalic(false)));
		stack.set(DataComponents.LORE, new ItemLore(List.of(
			Component.literal(effect + " for 30 seconds").withStyle(ChatFormatting.GRAY),
			Component.literal(trigger + ". Gone when time's up.").withStyle(ChatFormatting.DARK_GRAY))));
		stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
		// A wooden pickaxe at max Haste would wear out long before the 30 seconds do.
		stack.set(DataComponents.UNBREAKABLE, Unit.INSTANCE);
		CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> tag.putString(POWER_KEY, getSerializedName()));
		return stack;
	}

	/** The power this item carries, or null for anything else. */
	static @Nullable Power of(ItemStack stack) {
		CustomData data = stack.get(DataComponents.CUSTOM_DATA);
		if (data == null) {
			return null;
		}
		CompoundTag tag = data.copyTag();
		return tag.getString(POWER_KEY).map(CODEC::byName).orElse(null);
	}

	/** Whether this item has been used already, so its 30 seconds are running or over. */
	static boolean isUsed(ItemStack stack) {
		CustomData data = stack.get(DataComponents.CUSTOM_DATA);
		return data != null && data.copyTag().getBooleanOr(ACTIVE_KEY, false);
	}

	static void markUsed(ItemStack stack) {
		CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> tag.putBoolean(ACTIVE_KEY, true));
	}
}
