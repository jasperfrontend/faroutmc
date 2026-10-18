package nl.jasperfrontend.farout;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The player as they were before /farout start: where they stood, what they carried, their ender chest,
 * XP, health, hunger, effects and bed. A session starts them like a brand-new player in the run's world;
 * coming home gives all of it back, so playing Farout never costs anything in your own world. Attached
 * to the player and saved with them, so it survives death, logging out and even a crash mid-run.
 */
final class Stash {
	record Place(ResourceKey<Level> dimension, Vec3 pos, float yaw, float pitch) {
		static final Codec<Place> CODEC = RecordCodecBuilder.create(i -> i.group(
			ResourceKey.codec(Registries.DIMENSION).fieldOf("dimension").forGetter(Place::dimension),
			Vec3.CODEC.fieldOf("pos").forGetter(Place::pos),
			Codec.FLOAT.fieldOf("yaw").forGetter(Place::yaw),
			Codec.FLOAT.fieldOf("pitch").forGetter(Place::pitch)
		).apply(i, Place::new));
	}

	record Saved(Place place, List<ItemStack> inventory, int selectedSlot, List<ItemStack> enderChest,
		int xpLevel, int xpTotal, float xpProgress, float health, float absorption, int food, float saturation,
		List<MobEffectInstance> effects, Optional<ServerPlayer.RespawnConfig> respawn) {
		static final Codec<Saved> CODEC = RecordCodecBuilder.create(i -> i.group(
			Place.CODEC.fieldOf("place").forGetter(Saved::place),
			ItemStack.OPTIONAL_CODEC.listOf().fieldOf("inventory").forGetter(Saved::inventory),
			Codec.INT.fieldOf("selected_slot").forGetter(Saved::selectedSlot),
			ItemStack.OPTIONAL_CODEC.listOf().fieldOf("ender_chest").forGetter(Saved::enderChest),
			Codec.INT.fieldOf("xp_level").forGetter(Saved::xpLevel),
			Codec.INT.fieldOf("xp_total").forGetter(Saved::xpTotal),
			Codec.FLOAT.fieldOf("xp_progress").forGetter(Saved::xpProgress),
			Codec.FLOAT.fieldOf("health").forGetter(Saved::health),
			Codec.FLOAT.fieldOf("absorption").forGetter(Saved::absorption),
			Codec.INT.fieldOf("food").forGetter(Saved::food),
			Codec.FLOAT.fieldOf("saturation").forGetter(Saved::saturation),
			MobEffectInstance.CODEC.listOf().fieldOf("effects").forGetter(Saved::effects),
			ServerPlayer.RespawnConfig.CODEC.optionalFieldOf("respawn").forGetter(Saved::respawn)
		).apply(i, Saved::new));
	}

	private static final AttachmentType<Saved> TYPE = AttachmentRegistry.create(
		Identifier.fromNamespaceAndPath(Farout.MOD_ID, "stash"), builder -> builder.persistent(Saved.CODEC).copyOnDeath());

	private Stash() {
	}

	/** Loads the class, so the attachment is registered before any player data is read. */
	static void register() {
	}

	static boolean has(ServerPlayer player) {
		return player.hasAttached(TYPE);
	}

	/**
	 * Saves the player as they are now. Does nothing if a session already holds their stash: starting again
	 * from the hub or after a finish must not overwrite their real things with run ones.
	 */
	static void save(ServerPlayer player) {
		if (has(player)) {
			return;
		}
		player.setAttached(TYPE, new Saved(
			new Place(player.level().dimension(), player.position(), player.getYRot(), player.getXRot()),
			copy(player.getInventory()), player.getInventory().getSelectedSlot(), copy(player.getEnderChestInventory()),
			player.experienceLevel, player.totalExperience, player.experienceProgress,
			player.getHealth(), player.getAbsorptionAmount(),
			player.getFoodData().getFoodLevel(), player.getFoodData().getSaturationLevel(),
			player.getActiveEffects().stream().map(MobEffectInstance::new).toList(),
			Optional.ofNullable(player.getRespawnConfig())));
	}

	/** Puts the player back where they were with everything they had; returns false if there was nothing stashed. */
	static boolean restore(ServerPlayer player) {
		// Off first: the teleport below fires the level change event, which must see the session as over.
		Saved saved = player.removeAttached(TYPE);
		if (saved == null) {
			return false;
		}

		ServerLevel level = player.level().getServer().getLevel(saved.place.dimension);
		if (level == null) {
			// Their dimension is gone (a removed mod, say): the overworld's spawn will have to do.
			level = player.level().getServer().overworld();
			BlockPos pos = player.adjustSpawnLocation(level, level.getRespawnData().pos());
			player.teleportTo(level, pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, Set.of(), 0f, 0f, true);
		} else {
			Vec3 pos = saved.place.pos;
			player.teleportTo(level, pos.x, pos.y, pos.z, Set.of(), saved.place.yaw, saved.place.pitch, true);
		}

		fill(player.getInventory(), saved.inventory);
		player.getInventory().setSelectedSlot(saved.selectedSlot);
		fill(player.getEnderChestInventory(), saved.enderChest);
		player.setExperienceLevels(saved.xpLevel);
		player.totalExperience = saved.xpTotal;
		player.experienceProgress = saved.xpProgress;
		player.setHealth(saved.health);
		player.setAbsorptionAmount(saved.absorption);
		player.getFoodData().setFoodLevel(saved.food);
		player.getFoodData().setSaturation(saved.saturation);
		player.removeAllEffects();
		saved.effects.forEach(effect -> player.addEffect(new MobEffectInstance(effect)));
		player.setRespawnPosition(saved.respawn.orElse(null), false);
		PowerUps.clear(player);
		player.clearFire();
		player.setTicksFrozen(0);
		player.setAirSupply(player.getMaxAirSupply());
		player.resetFallDistance();
		return true;
	}

	private static List<ItemStack> copy(Container container) {
		List<ItemStack> items = new ArrayList<>(container.getContainerSize());
		for (int i = 0; i < container.getContainerSize(); i++) {
			items.add(container.getItem(i).copy());
		}
		return items;
	}

	private static void fill(Container container, List<ItemStack> items) {
		container.clearContent();
		for (int i = 0; i < Math.min(items.size(), container.getContainerSize()); i++) {
			container.setItem(i, items.get(i).copy());
		}
	}
}
