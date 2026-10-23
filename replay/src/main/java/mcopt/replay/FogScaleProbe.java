package mcopt.replay;

import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;

/** Opt-in reflection probe: verifies the companion's real render hook ran and followed live reach. */
final class FogScaleProbe {
    static JsonObject read() throws Exception {
        Class<?> holder=Class.forName("io.github.mintyleaf.atmosphericsfixes.FogReachScale$Holder");
        var field=holder.getDeclaredField("STATE");field.setAccessible(true);Object state=field.get(null);
        if(state==null)throw new AssertionError("Fog scaling API did not bind");
        JsonObject result=new JsonObject();
        for(String key:new String[]{"maximum","scale","lastAt"}) {
            var member=state.getClass().getDeclaredField(key);member.setAccessible(true);
            result.addProperty(key,(Number)member.get(state));
        }
        long last=result.get("lastAt").getAsLong();
        if(last==0 || System.nanoTime()-last>2_000_000_000L)throw new AssertionError("Fog scaling render hook was not active");
        float reach=(float)Class.forName("mcopt.metal.lod.Lod").getMethod("activeReach").invoke(null);
        reach=Math.max(reach,Minecraft.getInstance().options.getEffectiveRenderDistance()*16f);
        float maximum=result.get("maximum").getAsFloat(),scale=result.get("scale").getAsFloat();
        result.addProperty("reach",reach);result.addProperty("scaledMaximum",maximum*scale);
        if(Math.abs(maximum*scale-reach)>Math.max(1,reach*.02))throw new AssertionError("Fog scale did not follow settled live reach: "+result);
        return result;
    }
}
