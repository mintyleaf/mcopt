package mcopt.replay.mixin;

import com.mojang.blaze3d.pipeline.PipelineCache;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import java.util.Map;
import mcopt.replay.AssetTrace;
import mcopt.replay.Replay;
import org.spongepowered.asm.mixin.*;

@Mixin(PipelineCache.class)
abstract class PipelineReplayMixin {
    @Shadow @Final private Map<RenderPipeline,CompiledRenderPipeline> cache;
    @WrapMethod(method="get")
    private CompiledRenderPipeline mcoptReplay$miss(RenderPipeline pipeline,Operation<CompiledRenderPipeline> original) {
        if(Replay.observesStarPipeline() && pipeline.getLocation().getNamespace().equals("minecraft")
                && pipeline.getLocation().getPath().equals("ambientfog/position_color_triangles")
                && Replay.isStarPipeline(pipeline))Replay.starPipelineDraw();
        if(!Replay.traceClientWork() || cache.containsKey(pipeline))return original.call(pipeline);
        var event=AssetTrace.begin("pipeline",pipeline.getLocation().toString());
        if(event!=null){
            event.cache=System.identityHashCode(this);event.entries=cache.size();
            AssetTrace.describe(event,pipeline);
        }
        try{return original.call(pipeline);}finally{AssetTrace.end(event);}
    }
}
