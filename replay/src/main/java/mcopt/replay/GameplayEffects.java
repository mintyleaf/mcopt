package mcopt.replay;

import java.util.Map;
import jdk.jfr.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;

/** Opt-in first/warm resource exercise in the owned caving fixture, not a combat-input replay. */
final class GameplayEffects {
    private static final double[] TIMES = {2, 6, 10, 14, 18, 22, 26};
    private static int applied, commands, failures, broken, landed;
    private static boolean fallingBlocks,starTransition;
    private static double maxLag;
    private record Progress(int applied, int commands, int failures, int broken, int landed, double maxLag) { }
    private static volatile Progress progress = new Progress(0, 0, 0, 0, 0, 0);
    @Name("mcopt.ReplayGameplayEffect") @Label("Controlled gameplay resource exercise") @Category("mcopt") @StackTrace(false)
    static final class Effect extends Event { int index, cycle; String action; double scheduledSeconds, actualSeconds; }

    static void populate(ServerLevel level,boolean falling,boolean stars) {
        fallingBlocks=falling;starTransition=stars;
        EventType.getEventType(Effect.class);
        // Contain lava so it does not replace the room or spread unpredictably.
        for (int x=79; x<=81; x++) for (int z=524; z<=526; z++)
            level.setBlock(new BlockPos(x,49,z),(x==80 && z==525 ? Blocks.AIR : Blocks.STONE).defaultBlockState(),2);
        level.setBlock(new BlockPos(76,50,525),Blocks.COBBLESTONE.defaultBlockState(),2);
    }
    static void tick(ServerLevel level, double elapsed) {
        if (applied >= 14) return;
        int cycle = applied / TIMES.length, index = applied % TIMES.length;
        double scheduled = cycle*30 + TIMES[index];
        if (elapsed < scheduled) return; // At most one exercise per server tick.
        var event = new Effect();
        event.index=index; event.cycle=cycle; event.scheduledSeconds=scheduled; event.actualSeconds=elapsed; event.begin();
        switch(index) {
            case 0 -> {
                event.action="flame_and_lava_sound";
                // Populate already fixes noon; 26.3 rejects setting a clock to its current time.
                if(starTransition && cycle>0)command(level,"time set 6000");
                command(level,"particle minecraft:flame 80.5 50.2 525.5 0.4 0.2 0.4 0.01 24 force @a");
                command(level,"playsound minecraft:block.lava.ambient master @a 80.5 50 525.5 1 1");
                level.setBlock(new BlockPos(76,50,525),Blocks.COBBLESTONE.defaultBlockState(),3);
            }
            case 1 -> {
                event.action="zombie_and_skeleton";
                command(level,"summon minecraft:zombie 83.5 49 524.5 {NoAI:1b,PersistenceRequired:1b,Tags:[\"mcopt_probe_mob\",\"mcopt_probe_zombie\"]}");
                command(level,"summon minecraft:skeleton 77.5 49 524.5 {NoAI:1b,PersistenceRequired:1b,Tags:[\"mcopt_probe_mob\"]}");
            }
            case 2 -> {
                event.action="lava_burning_item_and_break_overlay";
                level.setBlock(new BlockPos(80,49,525),Blocks.LAVA.defaultBlockState(),3);
                command(level,"summon minecraft:item 80.5 49.2 525.5 {Item:{id:\"minecraft:oak_planks\",count:1}}");
                command(level,"particle minecraft:lava 80.5 50 525.5 0.2 0.2 0.2 0.01 12 force @a");
                level.destroyBlockProgress(987654,new BlockPos(76,50,525),5);
            }
            case 3 -> {
                event.action="block_destroy";
                if(level.destroyBlock(new BlockPos(76,50,525),true))broken++;
                level.destroyBlockProgress(987654,new BlockPos(76,50,525),-1);
                if(fallingBlocks){
                    level.setBlock(new BlockPos(74,49,525),Blocks.AIR.defaultBlockState(),3);
                    command(level,"summon minecraft:falling_block 74.5 53 525.5 {BlockState:{Name:\"minecraft:sand\"},Time:1,DropItem:0b}");
                }
            }
            case 4 -> {
                event.action="hurt_and_crit";
                if(fallingBlocks && level.getBlockState(new BlockPos(74,49,525)).is(Blocks.SAND))landed++;
                command(level,"damage @e[tag=mcopt_probe_zombie,limit=1] 1 minecraft:generic");
                command(level,"particle minecraft:crit 83.5 50 524.5 0.2 0.4 0.2 0.1 24 force @a");
            }
            case 5 -> {
                event.action="smoke_and_extinguish";
                command(level,"particle minecraft:smoke 80.5 50.2 525.5 0.3 0.2 0.3 0.01 24 force @a");
                command(level,"playsound minecraft:block.fire.extinguish master @a 80.5 50 525.5 1 1");
                level.setBlock(new BlockPos(80,49,525),Blocks.AIR.defaultBlockState(),3);
                if(starTransition)command(level,"time set 18000");
            }
            case 6 -> {
                event.action="mob_cleanup";
                command(level,"kill @e[tag=mcopt_probe_mob]");
            }
        }
        applied++; maxLag=Math.max(maxLag,(elapsed-scheduled)*1000);
        progress=new Progress(applied,commands,failures,broken,landed,maxLag);
        event.end(); event.commit();
        System.out.println("[mcopt-replay] effect cycle="+cycle+" action="+event.action+" failures="+failures);
    }
    private static void command(ServerLevel level,String command) {
        commands++;
        level.getServer().getCommands().performPrefixedCommand(level.getServer().createCommandSourceStack()
            .withCallback((success,result)->{if(!success)failures++;}),command);
    }
    static Map<String,Object> report() {
        var p=progress;
        int expected=22+(fallingBlocks?2:0)+(starTransition?3:0);
        return Map.of("applied",p.applied,"commands",p.commands,"failures",p.failures,
            "brokenBlocks",p.broken,"maxLagMs",p.maxLag,"fallingBlocks",fallingBlocks,"landedBlocks",p.landed,
            "starTransition",starTransition,"expectedCommands",expected,
            "valid",p.applied==14 && p.commands==expected && p.failures==0 && p.broken==2 && (!fallingBlocks || p.landed==2));
    }
}
