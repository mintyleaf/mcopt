package mcopt.replay.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import mcopt.replay.MoreCullingContext;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.At;

/** Experimental adapter confined to the owned replay harness. No Indigo/MoreCulling dependency in normal replay runs. */
@Pseudo
@Mixin(targets="net.fabricmc.fabric.impl.client.indigo.renderer.render.AltModelBlockRendererImpl",remap=false)
abstract class MoreCullingContextMixin {
    @Shadow private BlockAndTintGetter level;
    @Shadow private BlockPos pos;
    @Shadow @Final private BlockPos.MutableBlockPos scratchPos;

    @WrapOperation(method="shouldCullFace",at=@At(value="INVOKE",
        target="Lnet/minecraft/world/level/block/Block;shouldRenderFace(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/Direction;)Z"))
    private boolean mcoptReplay$worldAwareLeaves(BlockState state,BlockState neighbor,Direction face,Operation<Boolean> original)
        throws Throwable {
        if(MoreCullingContext.ENABLED && MoreCullingContext.applies(state))
            return MoreCullingContext.draw(state,neighbor,this.level,this.pos,face,this.scratchPos);
        return original.call(state,neighbor,face);
    }
}
