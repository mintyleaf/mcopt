package mcopt.replay;

import com.google.gson.GsonBuilder;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/** Actual installed renderer/API comparison over synthetic neighbors; never writes blocks or changes mod config. */
final class MoreCullingProbe {
    private MoreCullingProbe() { }

    static void run(Minecraft mc,Path output) throws Exception {
        Class<?> rendererType=Class.forName("net.fabricmc.fabric.api.client.renderer.v1.Renderer");
        Class<?> altType=Class.forName("net.fabricmc.fabric.api.client.renderer.v1.render.AltModelBlockRenderer");
        Class<?> emitterType=Class.forName("net.fabricmc.fabric.api.client.renderer.v1.mesh.QuadEmitter");
        Object renderer=rendererType.getMethod("get").invoke(null);
        Method tessellate=altType.getMethod("tesselateBlock",emitterType,float.class,float.class,float.class,
            BlockAndTintGetter.class,BlockPos.class,BlockState.class,BlockStateModel.class,long.class);
        Class<?> more=Class.forName("ca.fxco.moreculling.MoreCulling");
        Object config=more.getField("CONFIG").get(null);
        Method cull=Class.forName("ca.fxco.moreculling.utils.CullingUtils").getMethod("shouldDrawSideCulling",
            BlockState.class,BlockState.class,BlockGetter.class,BlockPos.class,Direction.class,BlockPos.class);
        BlockPos center=new BlockPos(mc.player.getBlockX(),mc.player.getBlockY(),mc.player.getBlockZ());
        List<Map<String,Object>> cases=new ArrayList<>();
        for(BlockState leaf:List.of(Blocks.OAK_LEAVES.defaultBlockState(),Blocks.MANGROVE_LEAVES.defaultBlockState())) {
            for(int shape=0;shape<3;shape++) {
                boolean surrounded=shape!=0;
                BlockPos sample=shape==2?center.offset(2,0,0):center;
                BlockAndTintGetter world=(BlockAndTintGetter)Proxy.newProxyInstance(MoreCullingProbe.class.getClassLoader(),
                    new Class<?>[]{BlockAndTintGetter.class},(proxy,method,args)->{
                        if(method.getName().equals("getBlockState")) {
                            BlockPos p=(BlockPos)args[0];
                            return p.equals(center) || surrounded && Math.abs(p.getX()-center.getX())<=2
                                && Math.abs(p.getY()-center.getY())<=2 && Math.abs(p.getZ()-center.getZ())<=2
                                ? leaf : Blocks.AIR.defaultBlockState();
                        }
                        if(method.getName().equals("getFluidState"))return net.minecraft.world.level.material.Fluids.EMPTY.defaultFluidState();
                        if(method.getName().equals("getBlockEntity"))return null;
                        try{return method.invoke(mc.level,args);}catch(InvocationTargetException e){throw e.getCause();}
                    });
                int[] emitted={0};
                Object emitter=rendererType.getMethod("quadEmitter",Consumer.class).invoke(renderer,(Consumer<Object>)quad->emitted[0]++);
                Object alt=rendererType.getMethod("altModelBlockRenderer",boolean.class,boolean.class,BlockColors.class)
                    .invoke(renderer,false,true,mc.getBlockColors());
                int expected=0;
                for(Direction face:Direction.values()) {
                    BlockPos neighbor=sample.relative(face);
                    if((boolean)cull.invoke(null,leaf,world.getBlockState(neighbor),world,sample,face,neighbor))expected++;
                }
                tessellate.invoke(alt,emitter,0f,0f,0f,world,sample,leaf,mc.getModelManager().getBlockStateModelSet().get(leaf),leaf.getSeed(sample));
                Map<String,Object> row=new LinkedHashMap<>();row.put("state",leaf.toString());row.put("surrounded",surrounded);
                row.put("shape",shape==0?"isolated":shape==1?"interior":"edge");
                row.put("worldAwareVisibleFaces",expected);row.put("actualIndigoQuads",emitted[0]);row.put("matches",expected==emitted[0]);cases.add(row);
            }
        }
        Map<String,Object> report=new LinkedHashMap<>();
        report.put("rendererClass",renderer.getClass().getName());
        report.put("leavesMode",config.getClass().getField("leavesCullingMode").get(config).toString());
        report.put("blockStateCulling",config.getClass().getField("useBlockStateCulling").get(config));
        report.put("cutoutLeaves",mc.options.cutoutLeaves().get());report.put("cases",cases);
        report.put("worldContextAdapter",MoreCullingContext.ENABLED);
        report.put("note","Same Indigo API entry point as OwnFrapi; vanilla cube leaf models. Synthetic proxy only; no world/config mutations. Counts compare emitted model quads with the installed MoreCulling world-aware helper.");
        Files.writeString(output.resolve("moreculling-probe.json"),new GsonBuilder().setPrettyPrinting().create().toJson(report)+"\n");
        if(MoreCullingContext.ENABLED && cases.stream().anyMatch(row->!Boolean.TRUE.equals(row.get("matches"))))
            throw new IllegalStateException("World-context adapter differs from installed MoreCulling rules; inspect moreculling-probe.json");
        System.out.println("[mcopt-replay] MoreCulling probe "+report.get("leavesMode")+": "+cases);
    }
}
