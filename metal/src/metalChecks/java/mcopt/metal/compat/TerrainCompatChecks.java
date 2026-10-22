package mcopt.metal.compat;

import java.lang.invoke.*;
import java.nio.file.Path;
import java.util.zip.ZipFile;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

/** Forwarding/fallback checks without optional-mod dependencies; real-model equivalence is exercised in replay. */
public final class TerrainCompatChecks {
	private static boolean eligible;
	private static int searches;
	private static BlockPos expected;
	private static RuntimeException failure;
	private static Object manager;
	private static Vec3 offset;
	private static boolean canHave(BlockAndTintGetter world, BlockPos pos) { check(world == null && pos == expected); return eligible; }
	private static BlockState layer(BlockAndTintGetter world, BlockPos pos) {
		check(world == null && pos == expected); searches++;
		if (failure != null) throw failure;
		return null;
	}
	private static Object manager() { return manager; }
	private static Vec3 offset(Object value, BlockState state, BlockPos pos) {
		check(value == manager && state == null && pos == expected);
		if (failure != null) throw failure;
		return offset;
	}
	private static void check(boolean result) { if (!result) throw new AssertionError(); }
	public static void main(String[] args) throws Throwable {
		var lookup = MethodHandles.lookup(); var self = TerrainCompatChecks.class;
		var snow = new BetterGrassSnow.Api(lookup.findStatic(self, "layer", MethodType.methodType(BlockState.class, BlockAndTintGetter.class, BlockPos.class)),
			lookup.findStatic(self, "canHave", MethodType.methodType(boolean.class, BlockAndTintGetter.class, BlockPos.class)),
			lookup.findStatic(self, "canHave", MethodType.methodType(boolean.class, BlockAndTintGetter.class, BlockPos.class)));
		expected = new BlockPos(17,201,-33);
		check(snow.ghost(null, expected) == null && searches == 0);
		eligible = true; check(snow.ghost(null, expected) == null && searches == 1);
		failure = new IllegalArgumentException("sentinel");
		try { snow.ghost(null, expected); throw new AssertionError("Snow failure swallowed"); }
		catch (IllegalArgumentException correct) { check(correct == failure); }
		failure = null;
		var api = new PolytoneOffset.Api(lookup.findStatic(self, "manager", MethodType.methodType(Object.class)),
			lookup.findStatic(self, "offset", MethodType.methodType(Vec3.class, Object.class, BlockState.class, BlockPos.class)));
		check(PolytoneOffset.get(null, null, expected) == null);
		check(PolytoneOffset.get(api, null, expected) == null);
		manager = new Object(); check(PolytoneOffset.get(api, null, expected) == null);
		offset = new Vec3(.25,-.125,.5); check(PolytoneOffset.get(api, null, expected) == offset);
		manager = new Object(); check(PolytoneOffset.get(api, null, expected) == offset);
		failure = new IllegalArgumentException("offset sentinel");
		try { PolytoneOffset.get(api, null, expected); throw new AssertionError("Offset failure swallowed"); }
		catch (IllegalArgumentException correct) { check(correct == failure); }
		check(BetterGrassSnow.supportedVersion("1.8.8+fabric.26.3") && !BetterGrassSnow.supportedVersion("1.8.9+fabric.26.3"));
		check(PolytoneOffset.supportedVersion("26.3-6.8.6") && !PolytoneOffset.supportedVersion("26.3-6.8.7"));
		try { BetterGrassSnow.bind(String.class); throw new AssertionError("Unsupported snow API accepted"); }
		catch (ReflectiveOperationException correct) { }
		try { PolytoneOffset.bind(String.class); throw new AssertionError("Unsupported offset API accepted"); }
		catch (ReflectiveOperationException correct) { }
		System.out.println("PASS: ineligible snow avoids neighbor search; offset override/null fallback/config replacement; forwarding, exceptions, version/API guards");
		if (args.length == 0) return;
		byte[] bytes;
		try (var jar = new ZipFile(Path.of(args[0]).toFile()); var in = jar.getInputStream(jar.getEntry("net/fabricmc/fabric/impl/client/indigo/renderer/render/AltModelBlockRendererImpl.class"))) { bytes = in.readAllBytes(); }
		ClassNode original = parse(bytes);
		var method = original.methods.stream().filter(m -> m.name.equals("tesselateBlock")).findFirst().orElseThrow();
		if (!PolytoneIndigoGuard.verify(original)) throw new AssertionError("Installed offset method rejected: " + LitematicaOverlayGuard.fingerprint(method));
		method.instructions.insert(new InsnNode(Opcodes.NOP)); check(!PolytoneIndigoGuard.verify(original));
		original = parse(bytes); original.name = "other/Renderer"; check(!PolytoneIndigoGuard.verify(original));
		System.out.println("PASS: actual Indigo offset method accepted; modified body/owner rejected");
	}
	private static ClassNode parse(byte[] bytes) { var node = new ClassNode(); new ClassReader(bytes).accept(node,0); return node; }
}
