package nl.jasperfrontend.farout;

import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.BossEvent;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Runs the crafted power-ups: starts each one on first use, counts its 30 seconds down in a boss bar,
 * and takes the item away when they're up. Nothing here can save you from the lane: its lightning kills
 * with generic_kill damage, which always goes through.
 */
public final class PowerUps {
	private static final int COFFEE_WINDOW_TICKS = 60 * 20;
	/** Haste's highest level; at this speed any pickaxe breaks stone instantly. */
	private static final int MAX_AMPLIFIER = 255;

	private static final Map<UUID, Map<Power, Countdown>> ACTIVE = new HashMap<>();
	/** Mountain Hop ran out mid-air: no fall damage until they're back on the ground. */
	private static final Set<UUID> LANDING = new HashSet<>();
	/** Asleep long enough for the night to be skipped, as of last tick. */
	private static final Set<UUID> SLEEPING = new HashSet<>();
	/** Just woke up: Morning Coffee can be crafted until this runs out. */
	private static final Map<UUID, Countdown> COFFEE_WINDOW = new HashMap<>();

	private PowerUps() {
	}

	static void register() {
		ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) ->
			!(entity instanceof ServerPlayer player) || allowDamage(player, source));

		PlayerBlockBreakEvents.AFTER.register((level, player, pos, state, blockEntity) -> {
			if (level instanceof ServerLevel serverLevel && player instanceof ServerPlayer serverPlayer) {
				afterBreak(serverLevel, serverPlayer, pos);
			}
		});

		// Right-clicking Morning Coffee drinks it, in the air or at a block (so it's never placed as a bed).
		UseItemCallback.EVENT.register((player, level, hand) -> drink(player, level, hand));
		UseBlockCallback.EVENT.register((player, level, hand, hit) -> drink(player, level, hand));
	}

	/** Gives every power-up recipe to the player, so they're in the recipe book from the start. */
	static void unlockRecipes(ServerPlayer player) {
		List<RecipeHolder<?>> recipes = new ArrayList<>();
		for (RecipeHolder<?> holder : player.level().getServer().getRecipeManager().getRecipes()) {
			if (holder.value() instanceof PowerUpRecipe) {
				recipes.add(holder);
			}
		}
		player.awardRecipes(recipes);
	}

	/**
	 * Whether this player may take this crafting result. Morning Coffee only brews in the minute after
	 * sleeping through the night; everything else always may. Called from CraftingMenuMixin.
	 */
	public static boolean mayCraft(Player player, ItemStack result) {
		return Power.of(result) != Power.MORNING_COFFEE || COFFEE_WINDOW.containsKey(player.getUUID());
	}

	static void tick(MinecraftServer server) {
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			UUID id = player.getUUID();

			ItemStack boots = player.getItemBySlot(EquipmentSlot.FEET);
			if (Power.of(boots) == Power.MOUNTAIN_HOP && !Power.isUsed(boots)) {
				activate(player, Power.MOUNTAIN_HOP, boots);
			}
			ItemStack using = player.getUseItem();
			if (player.isUsingItem() && Power.of(using) == Power.CANT_HURT_ME && !Power.isUsed(using)) {
				activate(player, Power.CANT_HURT_ME, using);
			}

			if (player.isSleepingLongEnough()) {
				SLEEPING.add(id);
			} else if (!player.isSleeping() && SLEEPING.remove(id)) {
				openCoffeeWindow(player);
			}

			Countdown window = COFFEE_WINDOW.get(id);
			if (window != null && window.tick()) {
				COFFEE_WINDOW.remove(id).end();
			}

			Map<Power, Countdown> active = ACTIVE.get(id);
			if (active != null) {
				active.entrySet().removeIf(entry -> {
					if (!entry.getValue().tick()) {
						return false;
					}
					entry.getValue().end();
					expire(player, entry.getKey());
					return true;
				});
			}

			if (player.onGround()) {
				LANDING.remove(id);
			}
		}
	}

	/** Ends every power-up and timer this player has, e.g. when a run starts or they die. */
	static void clear(ServerPlayer player) {
		UUID id = player.getUUID();
		Map<Power, Countdown> active = ACTIVE.remove(id);
		if (active != null) {
			active.values().forEach(Countdown::end);
		}
		Countdown window = COFFEE_WINDOW.remove(id);
		if (window != null) {
			window.end();
		}
		LANDING.remove(id);
		SLEEPING.remove(id);
	}

	static void clearAll() {
		ACTIVE.values().forEach(active -> active.values().forEach(Countdown::end));
		ACTIVE.clear();
		COFFEE_WINDOW.values().forEach(Countdown::end);
		COFFEE_WINDOW.clear();
		LANDING.clear();
		SLEEPING.clear();
	}

	private static boolean isActive(ServerPlayer player, Power power) {
		Map<Power, Countdown> active = ACTIVE.get(player.getUUID());
		return active != null && active.containsKey(power);
	}

	/** Starts a power-up from this item, unless the same power is already running. */
	private static boolean activate(ServerPlayer player, Power power, ItemStack stack) {
		Map<Power, Countdown> active = ACTIVE.computeIfAbsent(player.getUUID(), id -> new EnumMap<>(Power.class));
		if (active.containsKey(power)) {
			return false;
		}

		Power.markUsed(stack);
		active.put(power, new Countdown(player, power.label, power.colour, power.barColour, Power.DURATION_TICKS));
		switch (power) {
			case MOUNTAIN_HOP -> effect(player, MobEffects.JUMP_BOOST, 9);
			case POWER_DRILL -> effect(player, MobEffects.HASTE, MAX_AMPLIFIER);
			case MORNING_COFFEE -> effect(player, MobEffects.SPEED, 2);
			case CANT_HURT_ME -> {
				// Handled in allowDamage.
			}
		}
		sound(player, BuiltInRegistries.SOUND_EVENT.wrapAsHolder(SoundEvents.BEACON_ACTIVATE), 1f, 1.4f);
		return true;
	}

	private static void effect(ServerPlayer player, Holder<MobEffect> effect, int amplifier) {
		player.addEffect(new MobEffectInstance(effect, Power.DURATION_TICKS, amplifier, false, false, true));
	}

	/** Time's up: the used item is gone, wherever it is in the inventory (or on the cursor). */
	private static void expire(ServerPlayer player, Power power) {
		Inventory inventory = player.getInventory();
		for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
			ItemStack stack = inventory.getItem(slot);
			if (Power.of(stack) == power && Power.isUsed(stack)) {
				inventory.setItem(slot, ItemStack.EMPTY);
			}
		}
		ItemStack carried = player.containerMenu.getCarried();
		if (Power.of(carried) == power && Power.isUsed(carried)) {
			player.containerMenu.setCarried(ItemStack.EMPTY);
		}
		player.containerMenu.broadcastChanges();

		if (power == Power.MOUNTAIN_HOP && !player.onGround()) {
			LANDING.add(player.getUUID());
		}
		player.level().sendParticles(ParticleTypes.POOF, player.getX(), player.getY() + 1, player.getZ(),
			12, 0.3, 0.5, 0.3, 0.02);
		sound(player, SoundEvents.ITEM_BREAK, 1f, 1f);
	}

	private static boolean allowDamage(ServerPlayer player, DamageSource source) {
		// The lane's lightning (and /kill): nothing gets you out of that.
		if (source.is(DamageTypes.GENERIC_KILL)) {
			return true;
		}
		if (source.is(DamageTypeTags.IS_FALL)) {
			return !isActive(player, Power.MOUNTAIN_HOP) && !LANDING.contains(player.getUUID());
		}
		return !isActive(player, Power.CANT_HURT_ME);
	}

	/** The first block mined with a Power Drill starts it; while it runs, every block takes its 3x3 with it. */
	private static void afterBreak(ServerLevel level, ServerPlayer player, BlockPos pos) {
		ItemStack tool = player.getMainHandItem();
		if (Power.of(tool) != Power.POWER_DRILL) {
			return;
		}
		if (!Power.isUsed(tool) && !activate(player, Power.POWER_DRILL, tool)) {
			return;
		}
		if (!isActive(player, Power.POWER_DRILL)) {
			return;
		}

		// The 3x3 lies across the way you're looking: a wall ahead of you, or a floor below.
		Direction.Axis axis = player.getNearestViewDirection().getAxis();
		for (int a = -1; a <= 1; a++) {
			for (int b = -1; b <= 1; b++) {
				if (a == 0 && b == 0) {
					continue;
				}
				BlockPos target = switch (axis) {
					case X -> pos.offset(0, a, b);
					case Y -> pos.offset(a, 0, b);
					case Z -> pos.offset(a, b, 0);
				};
				BlockState state = level.getBlockState(target);
				if (state.isAir() || state.getBlock() instanceof LiquidBlock
					|| state.getDestroySpeed(level, target) < 0
					|| (state.requiresCorrectToolForDrops() && !tool.isCorrectToolForDrops(state))) {
					continue;
				}
				// No drops, or a drilled mountain fills the inventory with cobblestone.
				level.destroyBlock(target, false, player);
			}
		}
	}

	private static InteractionResult drink(Player player, Level level, InteractionHand hand) {
		ItemStack stack = player.getItemInHand(hand);
		if (Power.of(stack) != Power.MORNING_COFFEE) {
			return InteractionResult.PASS;
		}
		if (player instanceof ServerPlayer serverPlayer) {
			if (!activate(serverPlayer, Power.MORNING_COFFEE, stack)) {
				serverPlayer.sendOverlayMessage(Component.literal("Still buzzing from the last one")
					.withStyle(Power.MORNING_COFFEE.colour));
				return InteractionResult.FAIL;
			}
			stack.shrink(1);
			sound(serverPlayer, SoundEvents.GENERIC_DRINK, 1f, 1f);
		}
		return InteractionResult.SUCCESS;
	}

	private static void openCoffeeWindow(ServerPlayer player) {
		Countdown old = COFFEE_WINDOW.put(player.getUUID(), new Countdown(player, "Coffee time",
			Power.MORNING_COFFEE.colour, Power.MORNING_COFFEE.barColour, COFFEE_WINDOW_TICKS));
		if (old != null) {
			old.end();
		}
		player.sendSystemMessage(Component.literal("Good morning! Craft your bed into Morning Coffee within a minute.")
			.withStyle(Power.MORNING_COFFEE.colour));
	}

	private static void sound(ServerPlayer player, Holder<SoundEvent> sound, float volume, float pitch) {
		player.connection.send(new ClientboundSoundPacket(sound, SoundSource.MASTER,
			player.getX(), player.getY(), player.getZ(), volume, pitch, player.getRandom().nextLong()));
	}

	/** A boss bar that drains over the given time, showing the seconds left. */
	private static final class Countdown {
		private final ServerBossEvent bar;
		private final String label;
		private final ChatFormatting colour;
		private final int total;
		private int left;

		Countdown(ServerPlayer player, String label, ChatFormatting colour, BossEvent.BossBarColor barColour, int ticks) {
			this.label = label;
			this.colour = colour;
			this.total = ticks;
			this.left = ticks;
			this.bar = new ServerBossEvent(UUID.randomUUID(), name(), barColour, BossEvent.BossBarOverlay.NOTCHED_10);
			bar.addPlayer(player);
		}

		/** Counts one tick down; returns whether time is up. */
		boolean tick() {
			left--;
			bar.setProgress(Math.max(0f, (float) left / total));
			if (left % 20 == 0) {
				bar.setName(name());
			}
			return left <= 0;
		}

		void end() {
			bar.removeAllPlayers();
		}

		private Component name() {
			return Component.literal(label + "  " + (left + 19) / 20 + "s").withStyle(colour, ChatFormatting.BOLD);
		}
	}
}
