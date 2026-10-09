package nl.jasperfrontend.farout;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.item.crafting.ShapedRecipePattern;
import net.minecraft.world.item.crafting.display.RecipeDisplay;
import net.minecraft.world.item.crafting.display.ShapedCraftingRecipeDisplay;
import net.minecraft.world.item.crafting.display.SlotDisplay;

import java.util.List;

/**
 * A shaped 3x3 recipe whose result is the item in the middle of the grid, turned into a power-up. Being a
 * plain shaped recipe underneath, it shows up in the recipe book and fills the grid when clicked there.
 */
final class PowerUpRecipe extends ShapedRecipe {
	static final MapCodec<PowerUpRecipe> MAP_CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
			Recipe.CommonInfo.MAP_CODEC.forGetter(o -> o.commonInfo),
			CraftingRecipe.CraftingBookInfo.MAP_CODEC.forGetter(o -> o.bookInfo),
			ShapedRecipePattern.MAP_CODEC.forGetter(o -> o.pattern),
			ItemStackTemplate.CODEC.fieldOf("result").forGetter(o -> o.shown),
			Power.CODEC.fieldOf("power").forGetter(o -> o.power))
		.apply(i, PowerUpRecipe::new));
	static final StreamCodec<RegistryFriendlyByteBuf, PowerUpRecipe> STREAM_CODEC = StreamCodec.composite(
		Recipe.CommonInfo.STREAM_CODEC, o -> o.commonInfo,
		CraftingRecipe.CraftingBookInfo.STREAM_CODEC, o -> o.bookInfo,
		ShapedRecipePattern.STREAM_CODEC, o -> o.pattern,
		ItemStackTemplate.STREAM_CODEC, o -> o.shown,
		ByteBufCodecs.idMapper(id -> Power.values()[id], Power::ordinal), o -> o.power,
		PowerUpRecipe::new);
	static final RecipeSerializer<PowerUpRecipe> SERIALIZER = new RecipeSerializer<>(MAP_CODEC, STREAM_CODEC);

	private final ShapedRecipePattern pattern;
	/** What the recipe book shows as the result; the real result is whatever was in the middle. */
	private final ItemStackTemplate shown;
	final Power power;

	private PowerUpRecipe(Recipe.CommonInfo commonInfo, CraftingRecipe.CraftingBookInfo bookInfo,
		ShapedRecipePattern pattern, ItemStackTemplate shown, Power power) {
		super(commonInfo, bookInfo, pattern, shown);
		this.pattern = pattern;
		this.shown = shown;
		this.power = power;
	}

	@Override
	public ItemStack assemble(CraftingInput input) {
		// Every power-up recipe is a full 3x3 with the tool in the middle.
		return power.decorate(input.getItem(1, 1).copy());
	}

	@Override
	@SuppressWarnings("unchecked")
	public RecipeSerializer<ShapedRecipe> getSerializer() {
		return (RecipeSerializer<ShapedRecipe>) (RecipeSerializer<?>) SERIALIZER;
	}

	@Override
	public List<RecipeDisplay> display() {
		ItemStackTemplate result = ItemStackTemplate.fromNonEmptyStack(power.decorate(shown.create()));
		return List.of(new ShapedCraftingRecipeDisplay(
			pattern.width(),
			pattern.height(),
			pattern.ingredients().stream().map(e -> e.map(Ingredient::display).orElse(SlotDisplay.Empty.INSTANCE)).toList(),
			new SlotDisplay.ItemStackSlotDisplay(result),
			new SlotDisplay.ItemSlotDisplay(Items.CRAFTING_TABLE)));
	}
}
