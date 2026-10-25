package mcopt.probe.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import mcopt.probe.GameplayProbe;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import org.spongepowered.asm.mixin.Mixin;

/** One scope for the whole entity group, rather than allocating diagnostics for each mob. */
@Mixin(LevelExtractor.class)
abstract class EntityProbeMixin {
    @WrapMethod(method = "extractVisibleEntities")
    private void mcoptProbe$entities(Camera camera, Frustum frustum, DeltaTracker delta,
                                     LevelRenderState state, Operation<Void> original) {
        var event = GameplayProbe.begin("entityExtract");
        try { original.call(camera, frustum, delta, state); }
        finally { if (GameplayProbe.end(event)) event.commit(); }
    }
    @WrapMethod(method = "extractVisibleBlockEntities")
    private void mcoptProbe$blocks(Camera camera, float partialTick, LevelRenderState state, Operation<Void> original) {
        var event = GameplayProbe.begin("blockEntityExtract");
        try { original.call(camera, partialTick, state); }
        finally { if (GameplayProbe.end(event)) event.commit(); }
    }
    @WrapMethod(method = "extractBlockDestroyAnimation")
    private void mcoptProbe$breaking(Camera camera, LevelRenderState state, Operation<Void> original) {
        var event = GameplayProbe.begin("blockBreakingExtract");
        try { original.call(camera, state); }
        finally { if (GameplayProbe.end(event)) event.commit(); }
    }
}
