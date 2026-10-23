package mcopt.replay;

import jdk.jfr.*;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import net.minecraft.client.renderer.RenderPipelines;

/** First-use diagnostics confined to the controlled replay harness. */
public final class AssetTrace {
    public static void prepare() {
        // Register/resolve the diagnostic before warmup, not on the first asset miss.
        EventType.getEventType(Asset.class);
        describe(new Asset(),RenderPipelines.ITEM_CUTOUT);
    }
    @Name("mcopt.ReplayAsset") @Label("Replay asset first use") @Category("mcopt")
    @StackTrace(true) @Threshold("1 ms")
    public static final class Asset extends Event {
        public String kind, location, implementation, canonical, state;
        public int cache, entries;
        public boolean registeredIdentity;
    }
    public static Asset begin(String kind,String location) {
        if(!Replay.traceClientWork())return null;
        Asset e=new Asset();
        if(!e.isEnabled())return null;
        e.kind=kind;e.location=location;e.begin();return e;
    }
    public static void describe(Asset event,RenderPipeline pipeline) {
        event.implementation=pipeline.getClass().getName();
        var required=RenderPipelines.requiredPipelines();var optional=RenderPipelines.optionalPipelines();
        event.canonical=required.stream().anyMatch(p->p.getLocation().equals(pipeline.getLocation()))
                || optional.stream().anyMatch(p->p.getLocation().equals(pipeline.getLocation()))?"registered":"unregistered";
        event.registeredIdentity=required.stream().anyMatch(p->p==pipeline) || optional.stream().anyMatch(p->p==pipeline);
        event.state="itemField="+(pipeline==RenderPipelines.ITEM_CUTOUT)+"; cull="+pipeline.isCull()+"; defines="+pipeline.getShaderDefines()+"; depth="+pipeline.getDepthStencilState();
    }
    public static void end(Asset event){if(event!=null){event.end();event.commit();}}
}
