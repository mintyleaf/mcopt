package mcopt.metal.mixin.compat;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import mcopt.metal.compat.PolytoneIndigoGuard;
import mcopt.metal.compat.PolytoneOffset;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

@Pseudo
@Mixin(targets = "net.fabricmc.fabric.impl.client.indigo.renderer.render.AltModelBlockRendererImpl", remap = false)
abstract class PolytoneIndigoMixin {
	@WrapOperation(method = "tesselateBlock", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/world/level/block/state/BlockState;getOffset(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/phys/Vec3;"))
	private Vec3 mcopt$polytoneOffset(BlockState state, BlockPos pos, Operation<Vec3> original) {
		Vec3 offset = PolytoneIndigoGuard.verified ? PolytoneOffset.get(state, pos) : null;
		return offset != null ? offset : original.call(state, pos);
	}
}
