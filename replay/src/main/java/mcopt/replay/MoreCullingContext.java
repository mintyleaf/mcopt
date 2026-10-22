package mcopt.replay;

import java.lang.invoke.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;

/** Opt-in replay prototype, not a production compatibility patch. Calls the installed mod's own culling rules. */
public final class MoreCullingContext {
    public static final boolean ENABLED=!System.getProperty("mcopt.replay.plan","").isEmpty()
        && Boolean.getBoolean("mcopt.replay.moreCullingContext");
    private MoreCullingContext() { }

    private static final class Handles {
        static final MethodHandle CONFIG,ENABLED,CAN_CULL,DRAW;
        static {
            try {
                var lookup=MethodHandles.publicLookup();
                Class<?> more=Class.forName("ca.fxco.moreculling.MoreCulling");
                Class<?> config=more.getField("CONFIG").getType();
                CONFIG=lookup.findStaticGetter(more,"CONFIG",config).asType(MethodType.methodType(Object.class));
                ENABLED=lookup.findGetter(config,"useBlockStateCulling",boolean.class)
                    .asType(MethodType.methodType(boolean.class,Object.class));
                CAN_CULL=lookup.findVirtual(Class.forName("ca.fxco.moreculling.api.blockstate.MoreStateCulling"),
                    "moreculling$canCull",MethodType.methodType(boolean.class))
                    .asType(MethodType.methodType(boolean.class,BlockState.class));
                DRAW=lookup.findStatic(Class.forName("ca.fxco.moreculling.utils.CullingUtils"),"shouldDrawSideCulling",
                    MethodType.methodType(boolean.class,BlockState.class,BlockState.class,BlockGetter.class,
                        BlockPos.class,Direction.class,BlockPos.class));
            } catch(ReflectiveOperationException e) { throw new ExceptionInInitializerError(e); }
        }
    }

    public static boolean applies(BlockState state) throws Throwable {
        Object config=Handles.CONFIG.invokeExact();
        return (boolean)Handles.ENABLED.invokeExact(config) && (boolean)Handles.CAN_CULL.invokeExact(state);
    }

    public static boolean draw(BlockState state,BlockState neighbor,BlockGetter world,BlockPos pos,Direction face,BlockPos neighborPos)
        throws Throwable {
        return (boolean)Handles.DRAW.invokeExact(state,neighbor,world,pos,face,neighborPos);
    }
}
