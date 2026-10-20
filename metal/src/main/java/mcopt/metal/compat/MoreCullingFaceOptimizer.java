package mcopt.metal.compat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

/** Inlines one verified boolean-returning callback bridge, preserving the installed mod's own instructions. */
public final class MoreCullingFaceOptimizer implements Opcodes {
	private static final String SOURCE = "ca.fxco.moreculling.mixin.blockstates.Block_fabricDrawSideMixin";
	private static final String CALLBACK = "org/spongepowered/asm/mixin/injection/callback/CallbackInfoReturnable";
	private static final String ARGUMENTS = "Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/Direction;";
	private static final String FACE = "(" + ARGUMENTS + ")Z";
	private static final String HANDLER = "(" + ARGUMENTS + "L" + CALLBACK + ";)V";
	private MoreCullingFaceOptimizer() { }

	/** Unknown versions, another injector sharing the callback, or a different handler shape keep their original code. */
	public static boolean optimize(ClassNode target) {
		if (!target.name.equals("net/minecraft/world/level/block/Block")) return false;
		MethodNode handler = null, face = null;
		for (MethodNode m : target.methods) {
			if (m.name.equals("shouldRenderFace") && m.desc.equals(FACE)) face = m;
			if (m.desc.equals(HANDLER) && (m.access & ACC_STATIC) != 0 && fromMoreCulling(m)) {
				if (handler != null) return false;
				handler = m;
			}
		}
		if (handler == null || face == null || !face.tryCatchBlocks.isEmpty() || !handler.tryCatchBlocks.isEmpty()) return false;
		List<AbstractInsnNode> body = code(handler);
		if (!opcodes(body, GETSTATIC, GETFIELD, IFEQ, ALOAD, CHECKCAST, INVOKEINTERFACE, IFEQ, ALOAD,
			ALOAD, ALOAD, GETSTATIC, GETSTATIC, ALOAD, GETSTATIC, INVOKESTATIC, INVOKESTATIC, INVOKEVIRTUAL, RETURN)
			|| !local(body.get(3), 0) || !local(body.get(7), 3) || !local(body.get(8), 0)
			|| !local(body.get(9), 1) || !local(body.get(12), 2)
			|| !call(body.get(15), "java/lang/Boolean", "valueOf", "(Z)Ljava/lang/Boolean;")
			|| !call(body.get(16), CALLBACK, "setReturnValue", "(Ljava/lang/Object;)V")) return false;
		for (int i : new int[] {2, 6})
			if (nextCode(((JumpInsnNode) body.get(i)).label) != body.getLast()) return false;
		if (!((MethodInsnNode) body.get(14)).desc.endsWith(")Z")) return false;

		List<AbstractInsnNode> instructions = code(face);
		int site = -1;
		for (int i = 0; i < instructions.size(); i++) {
			if (call(instructions.get(i), target.name, handler.name, handler.desc)) {
				if (site != -1) return false;
				site = i;
			}
		}
		if (site < 10 || site + 6 >= instructions.size()) return false;
		List<AbstractInsnNode> bridge = instructions.subList(site - 10, site + 7);
		if (!opcodes(bridge, NEW, DUP, LDC, ICONST_1, INVOKESPECIAL, ASTORE, ALOAD, ALOAD, ALOAD, ALOAD,
			INVOKESTATIC, ALOAD, INVOKEVIRTUAL, IFEQ, ALOAD, INVOKEVIRTUAL, IRETURN)
			|| !((TypeInsnNode) bridge.getFirst()).desc.equals(CALLBACK)
			|| !call(bridge.get(4), CALLBACK, "<init>", "(Ljava/lang/String;Z)V")
			|| !local(bridge.get(6), 0) || !local(bridge.get(7), 1) || !local(bridge.get(8), 2)
			|| !call(bridge.get(12), CALLBACK, "isCancelled", "()Z")
			|| !call(bridge.get(15), CALLBACK, "getReturnValueZ", "()Z")) return false;
		int slot = ((VarInsnNode) bridge.get(5)).var;
		for (int i : new int[] {9, 11, 14}) if (!local(bridge.get(i), slot)) return false;
		for (int i = 0; i < instructions.size(); i++) {
			if (i >= site - 10 && i <= site + 6) continue;
			if (instructions.get(i) instanceof VarInsnNode v && v.var == slot) return false;
		}
		LabelNode resume = ((JumpInsnNode) bridge.get(13)).label;
		if (nextCode(resume) != nextCode(bridge.getLast().getNext())) return false;
		// No other branch or protected region may enter the removed allocation/cancellation bridge.
		for (AbstractInsnNode n = bridge.getFirst(); n != bridge.getLast(); n = n.getNext())
			if (n instanceof LabelNode) return false;

		InsnList replacement = new InsnList();
		for (int i = 0; i < 15; i++) {
			if (i == 7) continue; // callback receiver, not part of the underlying culling computation
			AbstractInsnNode n = body.get(i);
			if (n instanceof JumpInsnNode jump) replacement.add(new JumpInsnNode(jump.getOpcode(), resume));
			else replacement.add(n.clone(new HashMap<>()));
		}
		replacement.add(new InsnNode(IRETURN));
		face.instructions.insertBefore(bridge.getFirst(), replacement);
		AbstractInsnNode end = bridge.getLast().getNext();
		for (AbstractInsnNode n = bridge.getFirst(); n != end;) {
			AbstractInsnNode next = n.getNext(); face.instructions.remove(n); n = next;
		}
		face.maxStack = Math.max(face.maxStack, 8);
		return true;
	}
	private static boolean fromMoreCulling(MethodNode method) {
		if (method.visibleAnnotations == null) return false;
		for (AnnotationNode annotation : method.visibleAnnotations) {
			if (!annotation.desc.equals("Lorg/spongepowered/asm/mixin/transformer/meta/MixinMerged;") || annotation.values == null) continue;
			for (int i = 0; i < annotation.values.size(); i += 2)
				if (annotation.values.get(i).equals("mixin") && annotation.values.get(i + 1).equals(SOURCE)) return true;
		}
		return false;
	}
	private static List<AbstractInsnNode> code(MethodNode method) {
		List<AbstractInsnNode> result = new ArrayList<>();
		for (AbstractInsnNode n : method.instructions) if (n.getOpcode() >= 0) result.add(n);
		return result;
	}
	private static AbstractInsnNode nextCode(AbstractInsnNode node) {
		while (node != null && node.getOpcode() < 0) node = node.getNext();
		return node;
	}
	private static boolean opcodes(List<AbstractInsnNode> code, int... expected) {
		if (code.size() != expected.length) return false;
		for (int i = 0; i < expected.length; i++) if (code.get(i).getOpcode() != expected[i]) return false;
		return true;
	}
	private static boolean local(AbstractInsnNode instruction, int slot) { return instruction instanceof VarInsnNode v && v.var == slot; }
	private static boolean call(AbstractInsnNode instruction, String owner, String name, String desc) {
		return instruction instanceof MethodInsnNode m && m.owner.equals(owner) && m.name.equals(name) && m.desc.equals(desc);
	}
}
