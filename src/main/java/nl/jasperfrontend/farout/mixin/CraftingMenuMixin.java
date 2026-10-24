package nl.jasperfrontend.farout.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.item.ItemStack;
import nl.jasperfrontend.farout.PowerUps;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Recipes can't see who's crafting, so Morning Coffee's "only right after sleeping" is checked here. It's
 * checked where the result is first read, going into the result slot: at that call it would already be on
 * the stack, so only the client's copy would come out empty, and the next sync would put the coffee back.
 */
@Mixin(CraftingMenu.class)
abstract class CraftingMenuMixin {
	@ModifyVariable(method = "slotChangedCraftingGrid", ordinal = 0, at = @At(value = "LOAD", ordinal = 0))
	private static ItemStack farout$onlyAfterSleeping(ItemStack result, @Local(argsOnly = true) Player player) {
		return PowerUps.mayCraft(player, result) ? result : ItemStack.EMPTY;
	}
}
