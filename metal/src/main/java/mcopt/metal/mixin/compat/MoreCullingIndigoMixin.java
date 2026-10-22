package mcopt.metal.mixin.compat;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import mcopt.metal.compat.MoreCullingContext;
import mcopt.metal.compat.MoreCullingIndigoGuard;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.At;

/** Keep Indigo's per-block face cache, supplying the missing world/positions to MoreCulling's own rules. */
@Pseudo
@Mixin(targets = "net.fabricmc.fabric.impl.client.indigo.renderer.render.AltModelBlockRendererImpl", remap = false)
abstract class MoreCullingIndigoMixin {
	@Shadow private BlockAndTintGetter level;
	@Shadow private BlockPos pos;
	@Shadow @Final private BlockPos.MutableBlockPos scratchPos;

	@WrapOperation(method = "shouldCullFace", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/world/level/block/Block;shouldRenderFace(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/Direction;)Z"))
	private boolean mcopt$worldAwareCulling(BlockState state, BlockState neighbor, Direction face, Operation<Boolean> original) {
		if (MoreCullingIndigoGuard.verified) {
			int result = MoreCullingContext.face(state, neighbor, this.level, this.pos, face, this.scratchPos);
			if (result != MoreCullingContext.FALLBACK) return result != 0;
		}
		return original.call(state, neighbor, face);
	}
}
