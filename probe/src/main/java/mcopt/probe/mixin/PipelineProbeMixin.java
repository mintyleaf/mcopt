package mcopt.probe.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.pipeline.PipelineCache;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import java.util.Map;
import mcopt.probe.GameplayProbe;
import org.spongepowered.asm.mixin.*;

@Mixin(PipelineCache.class)
abstract class PipelineProbeMixin {
    @Shadow @Final private Map<RenderPipeline, CompiledRenderPipeline> cache;

    @WrapMethod(method = "get")
    private CompiledRenderPipeline mcoptProbe$pipeline(RenderPipeline pipeline, Operation<CompiledRenderPipeline> original) {
        if (!GameplayProbe.enabled() || cache.containsKey(pipeline)) return original.call(pipeline);
        var event = GameplayProbe.begin("pipelineCacheMiss");
        try { return original.call(pipeline); }
        finally {
            if (GameplayProbe.end(event)) {
                try { event.subject = String.valueOf(pipeline.getLocation()); }
                catch (RuntimeException unavailable) { event.subject = "<unavailable>"; }
                event.implementation = pipeline == null ? "null" : pipeline.getClass().getName();
                event.commit();
            }
        }
    }
}
