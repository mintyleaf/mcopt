package mcopt.replay;

import java.util.Map;
import jdk.jfr.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;

/** Explicit opt-in edits in the marker-owned replay save only. Recreated from the immutable fixture each run. */
final class TerrainChanges {
    static final int BATCHES=80;
    static final double BEGIN=10,PERIOD=.5;
    private static final int X=64,Y=48,Z=512;
    private static int applied,rocks,lights;
    private static long digest=0xcbf29ce484222325L;
    private static double maxLag;
    private record Progress(int batches,int rocks,int lights,long digest,double maxLag,double completedAt){}
    private static volatile Progress progress=new Progress(0,0,0,0xcbf29ce484222325L,0,0);
    @Name("mcopt.ReplayTerrainChange") @Label("Deterministic terrain edit batch") @Category("mcopt") @StackTrace(false)
    static final class Batch extends Event {int batch,rockChanges,lightChanges;double scheduledSeconds,actualSeconds,lagMs;}

    static void populate(ServerLevel level) {
        EventType.getEventType(Batch.class); // Resolve the diagnostic before warmup, rather than on the first edited frame.
        for(int z=0;z<32;z++)for(int x=0;x<32;x++)for(int y=0;y<12;y++) {
            boolean wall=x<2||x>=30||z<2||z>=30||y==0||y==11;
            level.setBlock(new BlockPos(X+x,Y+y,Z+z),(wall?Blocks.STONE:Blocks.AIR).defaultBlockState(),2);
        }
        // Constant lights make all four faces visible; the additional changing lights exercise actual light updates.
        for(int x:new int[]{4,27})for(int z:new int[]{4,27})level.setBlock(new BlockPos(X+x,Y+8,Z+z),Blocks.GLOWSTONE.defaultBlockState(),2);
        for(int i=0;i<4;i++)level.setBlock(light(i),Blocks.STONE.defaultBlockState(),2);
        var server=level.getServer();
        server.getCommands().performPrefixedCommand(server.createCommandSourceStack(),"time set 6000");
        server.getCommands().performPrefixedCommand(server.createCommandSourceStack(),"weather clear");
        System.out.println("[mcopt-replay] deterministic caving room prepared; edits start at measured +10s");
    }
    static BlockPos rock(int index) {
        int along=3+(index%8)*3;
        return switch(index/8){case 0->new BlockPos(X+along,Y+4,Z+30);case 1->new BlockPos(X+along,Y+4,Z+1);case 2->new BlockPos(X+1,Y+4,Z+along);default->new BlockPos(X+30,Y+4,Z+along);};
    }
    static BlockPos light(int index){return new BlockPos(X+(index%2==0?8:23),Y+1,Z+(index<2?8:23));}
    static void tick(ServerLevel level,double elapsed) {
        int due=Math.min(BATCHES,Math.max(0,(int)Math.floor((elapsed-BEGIN)/PERIOD)+1));
        if(applied>=due)return; // At most one batch per server tick, even after a long stall.
        int batch=applied;double scheduled=BEGIN+batch*PERIOD;
        Batch event=new Batch();event.begin();event.batch=batch;event.scheduledSeconds=scheduled;event.actualSeconds=elapsed;event.lagMs=Math.max(0,(elapsed-scheduled)*1000);
        for(int j=0;j<4;j++){
            int index=(batch*4+j)%32;BlockPos p=rock(index);boolean open=(batch/8)%2==0;
            if(level.setBlock(p,(open?Blocks.AIR:Blocks.STONE).defaultBlockState(),3)){rocks++;event.rockChanges++;}
            hash(p,open?1:2);
        }
        BlockPos p=light(batch%4);boolean lit=(batch/4)%2==0;
        if(level.setBlock(p,(lit?Blocks.GLOWSTONE:Blocks.STONE).defaultBlockState(),3)){lights++;event.lightChanges++;}
        hash(p,lit?3:2);applied++;maxLag=Math.max(maxLag,event.lagMs);
        progress=new Progress(applied,rocks,lights,digest,maxLag,elapsed);event.end();event.commit();
    }
    private static void hash(BlockPos p,int state){digest=(digest^p.asLong())*0x100000001b3L;digest=(digest^state)*0x100000001b3L;}
    static Map<String,Object> report(){var p=progress;return Map.of("fixtureMode","creative","heldItems","diamond_pickaxe/torch","expectedBatches",BATCHES,"completedBatches",p.batches,"rockChanges",p.rocks,"lightChanges",p.lights,"digest",Long.toUnsignedString(p.digest,16),"maxLagMs",p.maxLag,"completedAtSeconds",p.completedAt,"valid",p.batches==BATCHES&&p.rocks==320&&p.lights==80&&p.completedAt<=50.5);}
}
