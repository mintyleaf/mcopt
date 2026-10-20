package mcopt.metal.mixin.compat;

import net.minecraft.world.level.block.Block;
import org.spongepowered.asm.mixin.Mixin;

/** Runs the verified bridge optimization after MoreCulling's priority-2500 mixin. */
@Mixin(value = Block.class, priority = 900)
abstract class MoreCullingFaceMixin { }
