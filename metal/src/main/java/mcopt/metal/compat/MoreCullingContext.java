package mcopt.metal.compat;

import java.lang.invoke.*;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

/** Supplies the world context missing from the optional mod's state-only Block.shouldRenderFace bridge. */
public final class MoreCullingContext {
	public static final int FALLBACK = -1;
	private MoreCullingContext() { }

	private static final class Holder {
		static final @Nullable Api API = resolve();
	}

	/** Package-private bindings let checks exercise guards and forwarding without bootstrapping a game. */
	record Api(MethodHandle config, MethodHandle enabled, MethodHandle canCull, MethodHandle draw) {
		int face(BlockState state, BlockState neighbor, BlockGetter world, BlockPos pos, Direction face, BlockPos neighborPos) throws Throwable {
			Object settings = this.config.invokeExact();
			if (settings == null || !(boolean)this.enabled.invokeExact(settings) || !(boolean)this.canCull.invokeExact(state)) return FALLBACK;
			return (boolean)this.draw.invokeExact(state, neighbor, world, pos, face, neighborPos) ? 1 : 0;
		}
	}

	public static boolean available() { return Holder.API != null; }

	/** -1 preserves the original caller; 0/1 is the installed mod's result. Its runtime failures remain visible. */
	public static int face(BlockState state, BlockState neighbor, BlockGetter world, BlockPos pos, Direction face, BlockPos neighborPos) {
		return face(Holder.API, state, neighbor, world, pos, face, neighborPos);
	}

	static int face(@Nullable Api api, BlockState state, BlockState neighbor, BlockGetter world, BlockPos pos, Direction face, BlockPos neighborPos) {
		if (api == null || world == null || pos == null || neighborPos == null) return FALLBACK;
		try { return api.face(state, neighbor, world, pos, face, neighborPos); }
		catch (RuntimeException | Error failure) { throw failure; }
		catch (Throwable failure) { throw new IllegalStateException("MoreCulling world-aware face check failed", failure); }
	}

	private static @Nullable Api resolve() {
		var loader = FabricLoader.getInstance();
		if (!Boolean.parseBoolean(System.getProperty("mcopt.compat.moreCullingContext", "true"))
			|| !loader.getModContainer("moreculling").filter(mod -> supportedVersion(mod.getMetadata().getVersion().getFriendlyString())).isPresent()) return null;
		try {
			return bind(Class.forName("ca.fxco.moreculling.MoreCulling"), Class.forName("ca.fxco.moreculling.api.blockstate.MoreStateCulling"),
				Class.forName("ca.fxco.moreculling.utils.CullingUtils"));
		} catch (ReflectiveOperationException | LinkageError failure) {
			System.out.println("[mcopt] MoreCulling world context: unchanged (unsupported API)");
			return null;
		}
	}

	static boolean supportedVersion(String version) { return version.equals("1.9.0"); }

	static Api bind(Class<?> more, Class<?> stateApi, Class<?> utils) throws ReflectiveOperationException {
		var lookup = MethodHandles.publicLookup();
		Class<?> config = more.getField("CONFIG").getType();
		return new Api(lookup.findStaticGetter(more, "CONFIG", config).asType(MethodType.methodType(Object.class)),
			lookup.findGetter(config, "useBlockStateCulling", boolean.class).asType(MethodType.methodType(boolean.class, Object.class)),
			lookup.findVirtual(stateApi, "moreculling$canCull", MethodType.methodType(boolean.class))
				.asType(MethodType.methodType(boolean.class, BlockState.class)),
			lookup.findStatic(utils, "shouldDrawSideCulling", MethodType.methodType(boolean.class, BlockState.class, BlockState.class,
				BlockGetter.class, BlockPos.class, Direction.class, BlockPos.class)));
	}
}
