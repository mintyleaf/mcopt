package mcopt.metal.compat;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/** Uses a real Sponge-transformed Block plus the installed MoreCulling handler, supplied by the offline harness. */
public final class MoreCullingFaceChecks implements Opcodes {
	private static final String SELF = MoreCullingFaceChecks.class.getName().replace('.', '/');
	private static final String BLOCK = "net/minecraft/world/level/block/Block";
	private static final String CALLBACK = "org/spongepowered/asm/mixin/injection/callback/CallbackInfoReturnable";
	private static int beforeCalls, fallbackCalls, cullingCalls;
	private static volatile boolean consumed;

	public static void main(String[] args) throws Throwable {
		byte[] input = Files.readAllBytes(Path.of(args[0]));
		ClassNode original = parse(input), optimized = parse(input);
		if (!MoreCullingFaceOptimizer.optimize(optimized)) throw new AssertionError("Real MoreCulling bridge not recognized");
		if (allocations(original) != 1 || allocations(optimized) != 0) throw new AssertionError("Callback allocation remains");
		ClassNode unknown = parse(input);
		for (MethodNode m : unknown.methods) if (m.name.contains("customShouldDrawSide")) m.instructions.insert(new InsnNode(NOP));
		byte[] before = bytes(unknown);
		if (MoreCullingFaceOptimizer.optimize(unknown) || !java.util.Arrays.equals(before, bytes(unknown)))
			throw new AssertionError("Unrecognized handler was changed");
		ClassNode shared = parse(input);
		MethodNode face = face(shared); int slot = -1;
		for (AbstractInsnNode n : face.instructions) if (n.getOpcode() == ASTORE) { slot = ((VarInsnNode) n).var; break; }
		face.instructions.add(new VarInsnNode(ALOAD, slot)); face.instructions.add(new InsnNode(POP));
		if (MoreCullingFaceOptimizer.optimize(shared)) throw new AssertionError("Shared callback local was changed");
		ClassNode absent = parse(input);
		for (MethodNode m : absent.methods) if (m.name.contains("customShouldDrawSide")) m.visibleAnnotations = null;
		if (MoreCullingFaceOptimizer.optimize(absent)) throw new AssertionError("Foreign handler changed");

		MethodHandle baseline = fixture(original, "Original"), replacement = fixture(optimized, "Optimized");
		int combinations = 0;
		for (boolean enabled : new boolean[] {false, true}) for (boolean canCull : new boolean[] {false, true})
			for (int a = 0; a < 2; a++) for (int b = 0; b < 2; b++) for (int d = 0; d < 6; d++) {
				MoreCulling.CONFIG.useBlockStateCulling = enabled;
				BlockState state = new BlockState(a, canCull), neighbor = new BlockState(b, false); Direction direction = new Direction(d);
				int[] counts = new int[6]; boolean[] result = new boolean[2]; int j = 0;
				for (MethodHandle method : new MethodHandle[] {baseline, replacement}) {
					beforeCalls = fallbackCalls = cullingCalls = 0;
					result[j / 3] = (boolean) method.invokeExact(state, neighbor, direction);
					counts[j++] = beforeCalls; counts[j++] = fallbackCalls; counts[j++] = cullingCalls;
				}
				if (result[0] != result[1] || counts[0] != 1 || counts[3] != 1 || counts[1] != counts[4] || counts[2] != counts[5]
					|| counts[1] != (enabled && canCull ? 0 : 1) || counts[2] != (enabled && canCull ? 1 : 0))
					throw new AssertionError("Culling/fallback/other-hook behavior changed");
				combinations++;
			}
		MoreCulling.CONFIG.useBlockStateCulling = true;
		for (MethodHandle method : new MethodHandle[] {baseline, replacement}) {
			try { consumed = (boolean) method.invokeExact(new BlockState(99, true), new BlockState(0, false), new Direction(0)); throw new AssertionError("Exception swallowed"); }
			catch (IllegalArgumentException expected) { }
		}
		BlockState state = new BlockState(1, true), neighbor = new BlockState(0, false); Direction direction = new Direction(0);
		var bean = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean(); long thread = Thread.currentThread().threadId();
		for (MethodHandle method : new MethodHandle[] {baseline, replacement}) for (int i = 0; i < 100_000; i++) consumed = (boolean) method.invokeExact(state, neighbor, direction);
		long[] allocated = new long[2]; int j = 0;
		for (MethodHandle method : new MethodHandle[] {baseline, replacement}) {
			long start = bean.getThreadAllocatedBytes(thread);
			for (int i = 0; i < 500_000; i++) consumed = (boolean) method.invokeExact(state, neighbor, direction);
			allocated[j++] = bean.getThreadAllocatedBytes(thread) - start;
		}
		System.out.printf("PASS: real transformed hook, %d guard/state/direction cases, exceptions and surrounding hooks preserved; unknown/shared hooks unchanged%n", combinations);
		System.out.printf("Callback bridge allocated %.3f -> %.3f bytes/call (escape analysis disabled in this fixture only)%n", allocated[0] / 500_000.0, allocated[1] / 500_000.0);
		if (allocated[1] > 8192 || allocated[0] < 8_000_000) throw new AssertionError("Allocation fixture did not exercise callback churn");
	}
	private static ClassNode parse(byte[] bytes) { ClassNode node = new ClassNode(); new ClassReader(bytes).accept(node, 0); return node; }
	private static byte[] bytes(ClassNode node) { ClassWriter writer = new ClassWriter(0); node.accept(writer); return writer.toByteArray(); }
	private static MethodNode face(ClassNode node) { return node.methods.stream().filter(m -> m.name.equals("shouldRenderFace")).findFirst().orElseThrow(); }
	private static int allocations(ClassNode node) {
		int count = 0; for (AbstractInsnNode n : face(node).instructions) if (n instanceof TypeInsnNode t && t.getOpcode() == NEW && t.desc.equals(CALLBACK)) count++; return count;
	}
	private static MethodHandle fixture(ClassNode source, String suffix) throws Exception {
		ClassNode node = new ClassNode(); node.version = V25; node.access = ACC_PUBLIC; node.name = SELF + "$Generated" + suffix; node.superName = "java/lang/Object";
		MethodNode face = face(source); String handler = source.methods.stream().filter(m -> m.name.contains("customShouldDrawSide")).findFirst().orElseThrow().name;
		for (MethodNode m : source.methods) if (m == face || m.name.equals(handler)) {
			MethodNode copy = new MethodNode(m.access, m.name, m.desc, m.signature, m.exceptions.toArray(String[]::new));
			m.accept(copy); node.methods.add(copy);
		}
		face = face(node);
		// Substitute a sentinel for the vanilla tail: the real injected bridge and its original handler remain intact.
		LabelNode tail = null;
		if (suffix.equals("Original")) {
			for (AbstractInsnNode n : face.instructions) if (n instanceof MethodInsnNode call && call.name.equals("isCancelled")) {
				AbstractInsnNode next = n.getNext(); while (!(next instanceof JumpInsnNode)) next = next.getNext(); tail = ((JumpInsnNode) next).label; break;
			}
		} else for (AbstractInsnNode n : face.instructions) if (n instanceof JumpInsnNode jump) { tail = jump.label; break; }
		if (tail == null) throw new AssertionError("Tail not found");
		for (AbstractInsnNode n = tail.getNext(); n != null;) { AbstractInsnNode next = n.getNext(); face.instructions.remove(n); n = next; }
		face.instructions.add(new VarInsnNode(ALOAD, 0)); face.instructions.add(new VarInsnNode(ALOAD, 1)); face.instructions.add(new VarInsnNode(ALOAD, 2));
		face.instructions.add(new MethodInsnNode(INVOKESTATIC, SELF, "fallback", "(L"+SELF+"$BlockState;L"+SELF+"$BlockState;L"+SELF+"$Direction;)Z", false)); face.instructions.add(new InsnNode(IRETURN));
		face.instructions.insert(new MethodInsnNode(INVOKESTATIC, SELF, "before", "()V", false));
		Map<String,String> names = new HashMap<>(); names.put(BLOCK, node.name);
		for (Class<?> type : new Class<?>[] {BlockState.class, Direction.class, MoreStateCulling.class, BlockGetter.class, EmptyBlockGetter.class, BlockPos.class, MoreCulling.class, MoreCullingConfig.class, CullingUtils.class}) {
			String simple = type.getSimpleName(); String original = switch (simple) {
				case "BlockState" -> "net/minecraft/world/level/block/state/BlockState";
				case "Direction", "BlockPos" -> "net/minecraft/core/" + simple;
				case "BlockGetter", "EmptyBlockGetter" -> "net/minecraft/world/level/" + simple;
				case "MoreStateCulling" -> "ca/fxco/moreculling/api/blockstate/MoreStateCulling";
				case "MoreCulling" -> "ca/fxco/moreculling/MoreCulling";
				case "MoreCullingConfig" -> "ca/fxco/moreculling/config/MoreCullingConfig";
				default -> "ca/fxco/moreculling/utils/CullingUtils";
			}; names.put(original, type.getName().replace('.', '/'));
		}
		for (MethodNode m : node.methods) {
			m.desc = remap(m.desc, names); m.visibleAnnotations = null; m.invisibleAnnotations = null; m.localVariables = null;
			for (AbstractInsnNode n : m.instructions.toArray()) {
				if (n instanceof FrameNode) m.instructions.remove(n);
				else if (n instanceof TypeInsnNode t) t.desc = names.getOrDefault(t.desc, t.desc);
				else if (n instanceof FieldInsnNode f) { f.owner = names.getOrDefault(f.owner, f.owner); f.desc = remap(f.desc, names); }
				else if (n instanceof MethodInsnNode call) { call.owner = names.getOrDefault(call.owner, call.owner); call.desc = remap(call.desc, names); }
			}
		}
		ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS); node.accept(writer);
		Class<?> generated = new ClassLoader(MoreCullingFaceChecks.class.getClassLoader()) {
			Class<?> define() { byte[] data = writer.toByteArray(); return defineClass(null, data, 0, data.length); }
		}.define();
		return MethodHandles.publicLookup().findStatic(generated, "shouldRenderFace", MethodType.methodType(boolean.class, BlockState.class, BlockState.class, Direction.class));
	}
	private static String remap(String descriptor, Map<String,String> names) {
		for (var entry : names.entrySet()) descriptor = descriptor.replace("L" + entry.getKey() + ";", "L" + entry.getValue() + ";"); return descriptor;
	}
	public static void before() { beforeCalls++; }
	public static boolean fallback(BlockState state, BlockState neighbor, Direction direction) { fallbackCalls++; return ((state.value + neighbor.value + direction.value) & 1) == 0; }
	public interface MoreStateCulling { boolean moreculling$canCull(); }
	public record BlockState(int value, boolean canCull) implements MoreStateCulling { public boolean moreculling$canCull() { return canCull; } }
	public record Direction(int value) { }
	public interface BlockGetter { }
	public static final class EmptyBlockGetter implements BlockGetter { public static final EmptyBlockGetter INSTANCE = new EmptyBlockGetter(); }
	public static final class BlockPos { public static final BlockPos ZERO = new BlockPos(); }
	public static final class MoreCullingConfig { public boolean useBlockStateCulling; }
	public static final class MoreCulling { public static final MoreCullingConfig CONFIG = new MoreCullingConfig(); }
	public static final class CullingUtils {
		public static boolean shouldDrawSideCulling(BlockState state, BlockState neighbor, BlockGetter world, BlockPos pos, Direction direction, BlockPos neighborPos) {
			if (world != EmptyBlockGetter.INSTANCE || pos != BlockPos.ZERO || neighborPos != BlockPos.ZERO) throw new AssertionError("Culling arguments changed");
			cullingCalls++; if (state.value == 99) throw new IllegalArgumentException("fixture");
			return ((state.value ^ neighbor.value ^ direction.value) & 1) != 0;
		}
	}
}
