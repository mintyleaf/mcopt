package mcopt.metal.compat;

import java.lang.invoke.*;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/** Uses Polytone's own offset override, resolved only after Minecraft's mixins are ready. */
public final class PolytoneOffset {
	private PolytoneOffset() { }
	private static final class Holder { static final @Nullable Api API = resolve(); }
	record Api(MethodHandle manager, MethodHandle offset) {
		@Nullable Vec3 get(BlockState state, BlockPos pos) throws Throwable {
			Object value = this.manager.invokeExact();
			return value == null ? null : (Vec3)this.offset.invokeExact(value, state, pos);
		}
	}
	public static @Nullable Vec3 get(BlockState state, BlockPos pos) { return get(Holder.API, state, pos); }
	static @Nullable Vec3 get(@Nullable Api api, BlockState state, BlockPos pos) {
		if (api == null) return null;
		try { return api.get(state, pos); }
		catch (RuntimeException | Error failure) { throw failure; }
		catch (Throwable failure) { throw new IllegalStateException("Polytone block offset failed", failure); }
	}
	static boolean supportedVersion(String version) { return version.equals("26.3-6.8.6"); }
	private static @Nullable Api resolve() {
		if (!Boolean.parseBoolean(System.getProperty("mcopt.compat.polytoneOffset", "true"))
			|| FabricLoader.getInstance().getModContainer("polytone").filter(mod -> supportedVersion(mod.getMetadata().getVersion().getFriendlyString())).isEmpty()) return null;
		try { return bind(Class.forName("net.mehvahdjukaar.polytone.Polytone")); }
		catch (ReflectiveOperationException | LinkageError failure) {
			System.out.println("[mcopt] Polytone Indigo offset: unchanged (unsupported API)");
			return null;
		}
	}
	static Api bind(Class<?> polytone) throws ReflectiveOperationException {
		var lookup = MethodHandles.publicLookup();
		Class<?> manager = polytone.getField("BLOCK_MODIFIERS").getType();
		return new Api(lookup.findStaticGetter(polytone, "BLOCK_MODIFIERS", manager).asType(MethodType.methodType(Object.class)),
			lookup.findVirtual(manager, "maybeModifyOffset", MethodType.methodType(Vec3.class, BlockState.class, BlockPos.class))
				.asType(MethodType.methodType(Vec3.class, Object.class, BlockState.class, BlockPos.class)));
	}
}
