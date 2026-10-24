package nl.jasperfrontend.farout;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.phys.AABB;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * The bonus chest in the middle of the start area: diamond tools, dirt and steak, so a run is about running
 * instead of surviving. And one diamond: take it and the chest blows up, your inventory with it, and you're
 * left on half a heart for the rest of the run.
 * <p>
 * The loot is marked until the run starts, so it survives the reset at the portal while anything else picked
 * up in the start area doesn't. From the portal on it's plain items, stacking with whatever you find.
 */
final class BonusChest {
	/** Middle slot of the chest, for the diamond. */
	private static final int DIAMOND_SLOT = 13;
	private static final float GREEDY_HEALTH = 1f;
	/** How far around the chest a dropped diamond is still cleaned up when it blows. */
	private static final double DROP_REACH = 32;
	/** Extra blasts in a ring around the chest, and how far out, so it reads as one big explosion. */
	private static final int BLAST_RING = 6;
	private static final double BLAST_RADIUS = 3;

	private static final String LOOT_KEY = "farout_bonus";
	private static final String DIAMOND_KEY = "farout_greed";

	/** Where the chest is, in the session's world. */
	private record Placed(ServerLevel level, BlockPos pos) {
	}

	/** Every session that got a chest, until it ends; the chest itself may have blown up since. */
	private static final Map<UUID, Placed> PLACED = new HashMap<>();
	/** Chests whose diamond is still in them. */
	private static final Map<UUID, Placed> ARMED = new HashMap<>();
	/** Took the diamond: half a heart while they're in that world. */
	private static final Map<UUID, ServerLevel> GREEDY = new HashMap<>();

	private BonusChest() {
	}

	/** Puts the chest, filled, in the middle of the start area; c is the hub centre at feet level. */
	static void place(ServerPlayer player, ServerLevel level, BlockPos c) {
		level.setBlock(c, Blocks.CHEST.defaultBlockState(), Block.UPDATE_ALL);
		if (!(level.getBlockEntity(c) instanceof ChestBlockEntity chest)) {
			Farout.LOGGER.error("No chest block entity at {} after placing the bonus chest", c);
			return;
		}
		chest.setItem(1, loot(new ItemStack(Items.DIAMOND_PICKAXE)));
		chest.setItem(2, loot(new ItemStack(Items.DIAMOND_AXE)));
		chest.setItem(3, loot(new ItemStack(Items.DIAMOND_SHOVEL)));
		chest.setItem(4, loot(new ItemStack(Items.DIAMOND_HOE)));
		chest.setItem(5, loot(new ItemStack(Items.DIRT, 64)));
		chest.setItem(6, loot(new ItemStack(Items.DIRT, 64)));
		chest.setItem(7, loot(new ItemStack(Items.COOKED_BEEF, 64)));
		chest.setItem(DIAMOND_SLOT, diamond());

		Placed placed = new Placed(level, c.immutable());
		PLACED.put(player.getUUID(), placed);
		ARMED.put(player.getUUID(), placed);
	}

	/** Whether this session's start area got a bonus chest, so its run counts as a bonus chest run. */
	static boolean placed(ServerPlayer player) {
		return PLACED.containsKey(player.getUUID());
	}

	private static ItemStack loot(ItemStack stack) {
		CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> tag.putBoolean(LOOT_KEY, true));
		return stack;
	}

	private static ItemStack diamond() {
		ItemStack stack = new ItemStack(Items.DIAMOND);
		stack.set(DataComponents.CUSTOM_NAME, Component.literal("DO NOT TAKE THIS ELSE YOU'LL HAVE A BAD TIME")
			.withStyle(ChatFormatting.RED, ChatFormatting.BOLD).withStyle(s -> s.withItalic(false)));
		CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> tag.putBoolean(DIAMOND_KEY, true));
		return stack;
	}

	private static boolean isLoot(ItemStack stack) {
		CustomData data = stack.get(DataComponents.CUSTOM_DATA);
		return data != null && data.copyTag().getBooleanOr(LOOT_KEY, false);
	}

	static boolean isDiamond(ItemStack stack) {
		CustomData data = stack.get(DataComponents.CUSTOM_DATA);
		return data != null && data.copyTag().getBooleanOr(DIAMOND_KEY, false);
	}

	/** The same item without the loot mark, so it stacks with ordinary dirt and steak. */
	private static ItemStack plain(ItemStack stack) {
		ItemStack copy = stack.copy();
		CustomData.update(DataComponents.CUSTOM_DATA, copy, tag -> tag.remove(LOOT_KEY));
		return copy;
	}

	/**
	 * The run starts: returns the loot the player carries by inventory slot, as plain items, to give back after
	 * the reset. Whatever is still in the chest turns plain too.
	 */
	static Map<Integer, ItemStack> release(ServerPlayer player) {
		Map<Integer, ItemStack> carried = new HashMap<>();
		Inventory inventory = player.getInventory();
		for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
			if (isLoot(inventory.getItem(slot))) {
				carried.put(slot, plain(inventory.getItem(slot)));
			}
		}
		Placed placed = PLACED.get(player.getUUID());
		if (placed != null && placed.level.getBlockEntity(placed.pos) instanceof ChestBlockEntity chest) {
			for (int slot = 0; slot < chest.getContainerSize(); slot++) {
				if (isLoot(chest.getItem(slot))) {
					chest.setItem(slot, plain(chest.getItem(slot)));
				}
			}
		}
		return carried;
	}

	static void tick(MinecraftServer server) {
		Iterator<Map.Entry<UUID, Placed>> it = ARMED.entrySet().iterator();
		while (it.hasNext()) {
			Map.Entry<UUID, Placed> entry = it.next();
			ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
			Placed placed = entry.getValue();
			if (player != null && player.level() == placed.level && taken(player, placed)) {
				it.remove();
				blowUp(player, placed);
			}
		}

		for (Map.Entry<UUID, ServerLevel> entry : GREEDY.entrySet()) {
			ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
			if (player != null && player.level() == entry.getValue() && player.isAlive()) {
				if (player.getHealth() > GREEDY_HEALTH) {
					player.setHealth(GREEDY_HEALTH);
				}
				player.setAbsorptionAmount(0f);
			}
		}
	}

	/**
	 * Whether the diamond has been taken: it left the chest (clicked out, thrown out, pulled by a hopper), or
	 * the player picked it up after breaking the chest.
	 */
	private static boolean taken(ServerPlayer player, Placed placed) {
		if (isDiamond(player.containerMenu.getCarried()) || find(player.getInventory()) >= 0) {
			return true;
		}
		return placed.level.getBlockEntity(placed.pos) instanceof ChestBlockEntity chest && find(chest) < 0;
	}

	private static int find(Container container) {
		for (int slot = 0; slot < container.getContainerSize(); slot++) {
			if (isDiamond(container.getItem(slot))) {
				return slot;
			}
		}
		return -1;
	}

	/**
	 * The chest explodes with everything still in it, the player's inventory goes too (armour and off hand
	 * included, not the ender chest), and so does all but half a heart.
	 */
	private static void blowUp(ServerPlayer player, Placed placed) {
		ServerLevel level = placed.level;
		// Off the cursor first, or closing the chest's screen would drop it back into the inventory.
		player.containerMenu.setCarried(ItemStack.EMPTY);
		player.closeContainer();
		// After closing: a closing screen hands back whatever was in its crafting grid.
		player.getInventory().clearContent();
		player.inventoryMenu.getCraftSlots().clearContent();
		player.inventoryMenu.broadcastChanges();
		level.getEntitiesOfClass(ItemEntity.class, new AABB(placed.pos).inflate(DROP_REACH), e -> isDiamond(e.getItem()))
			.forEach(Entity::discard);
		if (level.getBlockEntity(placed.pos) instanceof ChestBlockEntity chest) {
			// Emptied first, or removing the block spills it all.
			chest.clearContent();
			level.setBlock(placed.pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
		}

		double x = placed.pos.getX() + 0.5;
		double y = placed.pos.getY() + 0.5;
		double z = placed.pos.getZ() + 0.5;
		// Looks and sounds only: a real explosion this size would wreck the start area and kill on the spot.
		level.sendParticles(ParticleTypes.EXPLOSION_EMITTER, x, y, z, 1, 0, 0, 0, 0);
		for (int i = 0; i < BLAST_RING; i++) {
			double angle = 2 * Math.PI * i / BLAST_RING;
			level.sendParticles(ParticleTypes.EXPLOSION_EMITTER,
				x + Math.cos(angle) * BLAST_RADIUS, y + 1, z + Math.sin(angle) * BLAST_RADIUS, 1, 0, 0, 0, 0);
		}
		level.sendParticles(ParticleTypes.LARGE_SMOKE, x, y + 1, z, 80, 2, 1.5, 2, 0.1);
		level.sendParticles(ParticleTypes.FLAME, x, y + 0.5, z, 60, 1.5, 1, 1.5, 0.15);
		level.playSound(null, x, y, z, SoundEvents.GENERIC_EXPLODE, SoundSource.BLOCKS, 8f, 0.6f);
		level.playSound(null, x, y, z, SoundEvents.GENERIC_EXPLODE, SoundSource.BLOCKS, 8f, 0.9f);

		player.setHealth(GREEDY_HEALTH);
		player.setAbsorptionAmount(0f);
		GREEDY.put(player.getUUID(), level);

		player.connection.send(new ClientboundSetTitlesAnimationPacket(5, 70, 20));
		player.connection.send(new ClientboundSetSubtitleTextPacket(
			Component.literal("You lost everything, and you're on half a heart")));
		player.connection.send(new ClientboundSetTitleTextPacket(
			Component.literal("GREEDY!").withStyle(ChatFormatting.RED, ChatFormatting.BOLD)));
		player.sendSystemMessage(Component.literal("You took the diamond, so the chest blew up. Everything in it is gone, "
			+ "and so is everything you were carrying. You're on half a heart, and it won't heal until this run is over.")
			.withStyle(ChatFormatting.RED));
	}

	/** The session is over, or starting again: no chest, no curse. */
	static void forget(ServerPlayer player) {
		UUID id = player.getUUID();
		PLACED.remove(id);
		ARMED.remove(id);
		GREEDY.remove(id);
	}

	static void forgetAll() {
		PLACED.clear();
		ARMED.clear();
		GREEDY.clear();
	}
}
