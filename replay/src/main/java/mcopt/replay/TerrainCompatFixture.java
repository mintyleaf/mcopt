package mcopt.replay;

import com.google.gson.GsonBuilder;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Predicate;
import net.minecraft.client.Minecraft;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;

/** Small visible test panels in the marker-owned disposable world only. No changes in the source save or pack. */
final class TerrainCompatFixture {
    private TerrainCompatFixture() { }
    static void populate(ServerLevel level) {
        for(int z=-2;z<=12;z++)for(int x=-2;x<=28;x++) {
            for(int y=301;y<=306;y++)level.setBlock(new BlockPos(x,y,z),Blocks.AIR.defaultBlockState(),2);
            BlockState floor=x<16?Blocks.GRASS_BLOCK.defaultBlockState():((x+z)%2==0?Blocks.IRON_BLOCK:Blocks.OBSIDIAN).defaultBlockState();
            level.setBlock(new BlockPos(x,300,z),floor,2);
        }
        panel(level,3,3,Blocks.SNOW.defaultBlockState());
        panel(level,11,3,Blocks.PINK_PETALS.defaultBlockState());
        panel(level,11,8,Blocks.MOSS_CARPET.defaultBlockState());
        for(int x=18;x<=24;x+=2) {
            level.setBlock(new BlockPos(x,301,3),Blocks.STONE.defaultBlockState(),2);
            level.setBlock(new BlockPos(x,301,7),Blocks.GRANITE.defaultBlockState(),2);
        }
        var server=level.getServer();
        server.getCommands().performPrefixedCommand(server.createCommandSourceStack(),"time set 6000");
        server.getCommands().performPrefixedCommand(server.createCommandSourceStack(),"weather clear");
        System.out.println("[mcopt-replay] terrain compatibility panels populated in owned world at Y300");
    }
    private static void panel(ServerLevel level,int x,int z,BlockState layer) {
        level.setBlock(new BlockPos(x,301,z),Blocks.OAK_FENCE.defaultBlockState(),2);
        for(int[] d:new int[][]{{-1,0},{1,0},{0,-1},{0,1}})level.setBlock(new BlockPos(x+d[0],301,z+d[1]),layer,2);
    }
    static void probe(Minecraft mc,Path output) throws Exception {
        List<Map<String,Object>> snowRows=new ArrayList<>();
        Class<?> snowType=Class.forName("mcopt.metal.compat.BetterGrassSnow");
        Object api=snowType.getMethod("get").invoke(null);
        if((api!=null)!=Boolean.parseBoolean(System.getProperty("mcopt.compat.betterGrassSnow","true")))
            throw new IllegalStateException("BetterGrassify production adapter activation differs from the probe request");
        Class<?> utils=Class.forName("dev.ultimatchamp.bettergrass.util.BetterSnowUtils");
        Method can=utils.getMethod("canHaveGhostLayer",BlockAndTintGetter.class,BlockPos.class);
        Method layer=utils.getMethod("getLayerNeighbour",BlockAndTintGetter.class,BlockPos.class);
        Method snow=utils.getMethod("isLayerNeighbourSnow",BlockAndTintGetter.class,BlockPos.class);
        for(BlockPos pos:List.of(new BlockPos(3,301,3),new BlockPos(11,301,3),new BlockPos(11,301,8),new BlockPos(20,301,3))) {
            BlockState expected=(boolean)can.invoke(null,mc.level,pos)?(BlockState)layer.invoke(null,mc.level,pos):null;
            BlockState actual=api==null?null:(BlockState)api.getClass().getMethod("ghost",BlockAndTintGetter.class,BlockPos.class).invoke(api,mc.level,pos);
            var row=new LinkedHashMap<String,Object>();row.put("pos",pos.toShortString());row.put("expectedGhost",String.valueOf(expected));row.put("actualGhost",String.valueOf(actual));
            row.put("matches",actual==expected);snowRows.add(row);
        }
        if(snowRows.getFirst().get("expectedGhost").equals("null") || snowRows.get(1).get("expectedGhost").equals("null") || snowRows.get(2).get("expectedGhost").equals("null"))
            throw new IllegalStateException("Ghost-layer fixture is not exercising the installed BetterGrassify rules");
        BlockPos ground=new BlockPos(3,300,3),above=ground.above();
        BlockState state=mc.level.getBlockState(ground);
        BlockState expectedSnow=(boolean)snow.invoke(null,mc.level,above) && (boolean)can.invoke(null,mc.level,above)?state.setValue(BlockStateProperties.SNOWY,true):state;
        BlockState actualSnow=api==null?state:(BlockState)api.getClass().getMethod("snowy",BlockAndTintGetter.class,BlockPos.class,BlockState.class).invoke(api,mc.level,ground,state);
        if(!expectedSnow.getValue(BlockStateProperties.SNOWY))throw new IllegalStateException("Snowy-ground fixture is ineffective");
        List<Map<String,Object>> offsetRows=new ArrayList<>();
        Class<?> polytone=Class.forName("net.mehvahdjukaar.polytone.Polytone");Object manager=polytone.getField("BLOCK_MODIFIERS").get(null);
        Method customOffset=manager.getClass().getMethod("maybeModifyOffset",BlockState.class,BlockPos.class);
        boolean offsetEnabled=Class.forName("mcopt.metal.compat.PolytoneIndigoGuard").getField("verified").getBoolean(null);
        if(offsetEnabled!=Boolean.parseBoolean(System.getProperty("mcopt.compat.polytoneOffset","true")))
            throw new IllegalStateException("Polytone production adapter activation differs from the probe request");
        for(BlockState block:List.of(Blocks.STONE.defaultBlockState(),Blocks.GRANITE.defaultBlockState(),Blocks.SHORT_GRASS.defaultBlockState())) {
            BlockPos pos=new BlockPos(20,301,3);
            Vec3 custom=(Vec3)customOffset.invoke(manager,block,pos),vanilla=block.getOffset(pos);
            if(block.is(Blocks.STONE) && (custom==null || custom.distanceToSqr(vanilla)<.000001))throw new IllegalStateException("Polytone fixture resource pack did not override the stone offset");
            Vec3 expected=offsetEnabled && custom!=null?custom:vanilla;
            double[] bounds=emitBounds(mc,block,pos,true),raw=emitBounds(mc,block,pos,false);
            boolean matches=Math.abs(bounds[0]-raw[0]-expected.x)<.00001 && Math.abs(bounds[1]-raw[1]-expected.y)<.00001 && Math.abs(bounds[2]-raw[2]-expected.z)<.00001;
            var row=new LinkedHashMap<String,Object>();row.put("state",block.toString());row.put("customOffset",String.valueOf(custom));row.put("vanillaOffset",vanilla.toString());row.put("expectedOffset",expected.toString());row.put("rawMin",raw);row.put("emittedMin",bounds);row.put("matches",matches);offsetRows.add(row);
        }
        var report=new LinkedHashMap<String,Object>();report.put("snowAdapter",api!=null);report.put("ghostLayers",snowRows);
        report.put("expectedSnowyGround",expectedSnow.toString());report.put("actualSnowyGround",actualSnow.toString());report.put("snowyGroundMatches",expectedSnow==actualSnow);
        report.put("polytoneAdapter",offsetEnabled);report.put("offsets",offsetRows);
        Files.writeString(output.resolve("terrain-compat-probe.json"),new GsonBuilder().setPrettyPrinting().create().toJson(report)+"\n");
        if(api!=null && (snowRows.stream().anyMatch(r->!Boolean.TRUE.equals(r.get("matches"))) || expectedSnow!=actualSnow)
            || offsetRows.stream().anyMatch(r->!Boolean.TRUE.equals(r.get("matches"))))throw new IllegalStateException("Production terrain compatibility probe failed");
        System.out.println("[mcopt-replay] terrain compatibility probes complete; snow="+(api!=null)+", offset="+offsetEnabled);
    }
    private static double[] emitBounds(Minecraft mc,BlockState state,BlockPos pos,boolean tesselate) throws Exception {
        Class<?> rendererType=Class.forName("net.fabricmc.fabric.api.client.renderer.v1.Renderer");
        Class<?> altType=Class.forName("net.fabricmc.fabric.api.client.renderer.v1.render.AltModelBlockRenderer");
        Class<?> emitterType=Class.forName("net.fabricmc.fabric.api.client.renderer.v1.mesh.QuadEmitter");
        Class<?> view=Class.forName("net.fabricmc.fabric.api.client.renderer.v1.mesh.QuadView");
        Method x=view.getMethod("x",int.class),y=view.getMethod("y",int.class),z=view.getMethod("z",int.class);
        double[] bounds={Double.POSITIVE_INFINITY,Double.POSITIVE_INFINITY,Double.POSITIVE_INFINITY};
        Object renderer=rendererType.getMethod("get").invoke(null);
        Object emitter=rendererType.getMethod("quadEmitter",Consumer.class).invoke(renderer,(Consumer<Object>)quad->{
            try {for(int i=0;i<4;i++){bounds[0]=Math.min(bounds[0],((Number)x.invoke(quad,i)).doubleValue());bounds[1]=Math.min(bounds[1],((Number)y.invoke(quad,i)).doubleValue());bounds[2]=Math.min(bounds[2],((Number)z.invoke(quad,i)).doubleValue());}}
            catch(Exception e){throw new IllegalStateException(e);}
        });
        BlockStateModel model=mc.getModelManager().getBlockStateModelSet().get(state);
        if(tesselate) {
            Object alt=rendererType.getMethod("altModelBlockRenderer",boolean.class,boolean.class,BlockColors.class).invoke(renderer,false,false,mc.getBlockColors());
            altType.getMethod("tesselateBlock",emitterType,float.class,float.class,float.class,BlockAndTintGetter.class,BlockPos.class,BlockState.class,BlockStateModel.class,long.class)
                .invoke(alt,emitter,0f,0f,0f,mc.level,pos,state,model,state.getSeed(pos));
        } else Class.forName("net.fabricmc.fabric.api.client.renderer.v1.model.FabricBlockStateModel")
            .getMethod("emitQuads",emitterType,BlockAndTintGetter.class,BlockPos.class,BlockState.class,RandomSource.class,Predicate.class)
            .invoke(model,emitter,mc.level,pos,state,RandomSource.create(state.getSeed(pos)),(Predicate<Object>)face->false);
        return bounds;
    }
}
