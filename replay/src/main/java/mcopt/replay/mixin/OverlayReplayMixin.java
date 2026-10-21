package mcopt.replay.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import mcopt.replay.Replay;
import net.minecraft.client.Camera;
import net.minecraft.util.profiling.ProfilerFiller;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;

/** Development-only timing of the caller; leaves the renderer bodies used by compatibility guards untouched. */
@Pseudo
@Mixin(targets = "fi.dy.masa.litematica.render.LitematicaRenderer", remap = false)
abstract class OverlayReplayMixin {
    @WrapMethod(method = "renderSchematicOverlays")
    private void mcoptReplay$overlay(Camera camera, ProfilerFiller profiler, Operation<Void> original) {
        var sample=Replay.overlaySample();
        if(sample!=null)sample.begin();
        try { original.call(camera,profiler); }
        finally { if(sample!=null){sample.end();sample.commit();} }
    }
}
