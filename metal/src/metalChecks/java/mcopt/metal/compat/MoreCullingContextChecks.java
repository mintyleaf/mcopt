package mcopt.metal.compat;

import java.lang.invoke.*;
import java.nio.file.Path;
import java.util.zip.ZipFile;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

/** Optional-API guards plus argument/result/exception forwarding; also verifies the actual installed Indigo body. */
public final class MoreCullingContextChecks {
	private record Settings(boolean enabled) { }
	private static Object settings = new Settings(true);
	private static boolean canCull = true, visible = true;
	private static int stateChecks, drawCalls;
	private static RuntimeException failure;
	private static BlockGetter world;
	private static BlockPos pos, neighbor;
	private static Direction direction;
	private static Object config() { return settings; }
	private static boolean enabled(Object value) { return ((Settings)value).enabled(); }
	private static boolean canCull(BlockState state) { stateChecks++; return canCull; }
	private static boolean draw(BlockState state, BlockState other, BlockGetter actualWorld, BlockPos actualPos, Direction face, BlockPos adjacent) {
		if (actualWorld != world || actualPos != pos || adjacent != neighbor || face != direction) throw new AssertionError("World/positions/direction changed");
		drawCalls++;
		if (failure != null) throw failure;
		return visible;
	}
	private static void expect(boolean condition) { if (!condition) throw new AssertionError(); }

	public static void main(String[] args) throws Throwable {
		var lookup = MethodHandles.lookup(); Class<?> self = MoreCullingContextChecks.class;
		var api = new MoreCullingContext.Api(lookup.findStatic(self,"config",MethodType.methodType(Object.class)),
			lookup.findStatic(self,"enabled",MethodType.methodType(boolean.class,Object.class)),
			lookup.findStatic(self,"canCull",MethodType.methodType(boolean.class,BlockState.class)),
			lookup.findStatic(self,"draw",MethodType.methodType(boolean.class,BlockState.class,BlockState.class,BlockGetter.class,BlockPos.class,Direction.class,BlockPos.class)));
		world = EmptyBlockGetter.INSTANCE; pos = new BlockPos(123,45,-67); direction = Direction.WEST; neighbor = pos.relative(direction);
		expect(MoreCullingContext.face(null,null,null,world,pos,direction,neighbor) == -1);
		expect(MoreCullingContext.face(api,null,null,null,pos,direction,neighbor) == -1);
		settings = null; expect(MoreCullingContext.face(api,null,null,world,pos,direction,neighbor) == -1);
		settings = new Settings(false); expect(MoreCullingContext.face(api,null,null,world,pos,direction,neighbor) == -1 && stateChecks == 0 && drawCalls == 0);
		settings = new Settings(true); canCull = false;
		expect(MoreCullingContext.face(api,null,null,world,pos,direction,neighbor) == -1 && drawCalls == 0);
		canCull = true;
		for (Direction face : Direction.values()) {
			direction = face; neighbor = pos.relative(face);
			for (boolean result : new boolean[] {false,true}) {
				visible = result; expect(MoreCullingContext.face(api,null,null,world,pos,direction,neighbor) == (result ? 1 : 0));
			}
		}
		failure = new IllegalArgumentException("sentinel");
		try { MoreCullingContext.face(api,null,null,world,pos,direction,neighbor); throw new AssertionError("Culling exception swallowed"); }
		catch (IllegalArgumentException expected) { expect(expected == failure); }
		expect(MoreCullingContext.supportedVersion("1.9.0") && !MoreCullingContext.supportedVersion("1.9.1"));
		try { MoreCullingContext.bind(String.class,String.class,String.class); throw new AssertionError("Unsupported API accepted"); }
		catch (ReflectiveOperationException expected) { }
		System.out.println("PASS: absent API, global/state disabled, configuration replacement, all face results/arguments, runtime exceptions and unsupported API guards");

		if (args.length == 0) return;
		byte[] bytes;
		try (var jar = new ZipFile(Path.of(args[0]).toFile()); var in = jar.getInputStream(jar.getEntry("net/fabricmc/fabric/impl/client/indigo/renderer/render/AltModelBlockRendererImpl.class"))) {
			bytes = in.readAllBytes();
		}
		ClassNode original = parse(bytes);
		MethodNode method = original.methods.stream().filter(m -> m.name.equals("shouldCullFace")).findFirst().orElseThrow();
		if (!MoreCullingIndigoGuard.verify(original)) throw new AssertionError("Installed Indigo body rejected: " + LitematicaOverlayGuard.fingerprint(method));
		ClassNode changed = parse(bytes);
		changed.methods.stream().filter(m -> m.name.equals("shouldCullFace")).findFirst().orElseThrow().instructions.insert(new InsnNode(Opcodes.NOP));
		expect(!MoreCullingIndigoGuard.verify(changed));
		changed = parse(bytes); changed.fields.stream().filter(f -> f.name.equals("level")).findFirst().orElseThrow().desc = "Ljava/lang/Object;";
		expect(!MoreCullingIndigoGuard.verify(changed));
		System.out.println("PASS: actual Indigo body accepted; modified body and incompatible world field rejected");
	}
	private static ClassNode parse(byte[] bytes) { ClassNode node = new ClassNode(); new ClassReader(bytes).accept(node,0); return node; }
}
