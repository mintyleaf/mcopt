package mcopt.replay;

import java.nio.file.Path;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;

/** Generated synthetic schematic only. No user schematic, placement or world is copied. */
final class SchematicFixture {
    private SchematicFixture() { }
    static void load(Path output) throws Exception {
        int nx=96,ny=24,nz=96,total=nx*ny*nz,blocks=0;
        long[] packed=new long[(total+31)/32];
        for(int y=0;y<ny;y++) for(int z=0;z<nz;z++) for(int x=0;x<nx;x++) {
            int state=(x%16==0 || z%16==0 || y==0 || y==ny-1) ? ((x+z+y)%7==0?2:1) : 0;
            if(state!=0)blocks++;
            int i=y*nx*nz+z*nx+x;packed[i>>5]|=(long)state<<((i&31)*2);
        }
        ListTag palette=new ListTag();
        for(String name:new String[]{"minecraft:air","minecraft:stone","minecraft:oak_stairs"}) {
            CompoundTag entry=new CompoundTag();entry.putString("Name",name);
            if(name.endsWith("stairs")) {CompoundTag props=new CompoundTag();props.putString("facing","east");props.putString("half","bottom");props.putString("shape","straight");props.putString("waterlogged","false");entry.put("Properties",props);}
            palette.add(entry);
        }
        CompoundTag region=new CompoundTag();region.put("Position",xyz(0,0,0));region.put("Size",xyz(nx,ny,nz));
        region.put("BlockStatePalette",palette);region.putLongArray("BlockStates",packed);
        for(String key:new String[]{"TileEntities","Entities","PendingBlockTicks","PendingFluidTicks"})region.put(key,new ListTag());
        CompoundTag regions=new CompoundTag();regions.put("Fixture",region);
        CompoundTag metadata=new CompoundTag();metadata.putString("Name","mcopt overlay fixture");metadata.putString("Author","mcopt replay");
        metadata.putInt("RegionCount",1);metadata.putInt("TotalVolume",total);metadata.putInt("TotalBlocks",blocks);
        metadata.putLong("TimeCreated",0);metadata.putLong("TimeModified",0);metadata.put("EnclosingSize",xyz(nx,ny,nz));
        CompoundTag root=new CompoundTag();root.putInt("Version",6);root.putInt("SubVersion",1);
        root.putInt("MinecraftDataVersion",SharedConstants.getCurrentVersion().dataVersion().version());root.put("Metadata",metadata);root.put("Regions",regions);
        Path file=output.resolve("overlay-fixture.litematic");NbtIo.writeCompressed(root,file);
        ClassLoader loader=SchematicFixture.class.getClassLoader();
        Class<?> data=Class.forName("fi.dy.masa.litematica.data.DataManager",true,loader);
        Object manager=data.getMethod("getSchematicPlacementManager").invoke(null);
        manager.getClass().getMethod("clear").invoke(manager);
        Class<?> holder=Class.forName("fi.dy.masa.litematica.data.SchematicHolder",true,loader);
        Object loaded=holder.getMethod("getInstance").invoke(null);
        holder.getMethod("clearLoadedSchematics").invoke(loaded);
        Class<?> schematic=Class.forName("fi.dy.masa.litematica.schematic.LitematicaSchematic",true,loader);
        Object instance=schematic.getMethod("createFromFile",Path.class,String.class).invoke(null,file.getParent(),file.getFileName().toString());
        if(instance==null)throw new IllegalStateException("Generated schematic could not be read");
        holder.getMethod("addSchematic",schematic,boolean.class).invoke(loaded,instance,false);
        Class<?> placement=Class.forName("fi.dy.masa.litematica.schematic.placement.SchematicPlacement",true,loader);
        Object placed=placement.getMethod("createFor",schematic,BlockPos.class,String.class,boolean.class,boolean.class,java.util.UUID.class)
            .invoke(null,instance,new BlockPos(0,192,0),"mcopt overlay fixture",true,true,new java.util.UUID(1,2));
        manager.getClass().getMethod("addSchematicPlacement",placement,boolean.class).invoke(manager,placed,false);
        System.out.println("[mcopt-replay] synthetic overlay fixture loaded: "+blocks+" blocks across 36 chunks");
    }
    static void populate(net.minecraft.server.level.ServerLevel level) {
        for(int z=0;z<96;z+=16) for(int x=0;x<96;x+=16) {
            level.setBlock(new BlockPos(x,193,z+5),net.minecraft.world.level.block.Blocks.DIRT.defaultBlockState(),3);
            level.setBlock(new BlockPos(x+2,193,z+2),net.minecraft.world.level.block.Blocks.STONE.defaultBlockState(),3);
        }
    }
    private static CompoundTag xyz(int x,int y,int z) {CompoundTag t=new CompoundTag();t.putInt("x",x);t.putInt("y",y);t.putInt("z",z);return t;}
}
