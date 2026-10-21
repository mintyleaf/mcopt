package mcopt.metal.compat;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.IdentityHashMap;
import java.util.Map;
import org.objectweb.asm.tree.*;

/** Only the inspected overlay orchestration/body is redirected; unknown or already-modified bodies keep their draws. */
public final class LitematicaOverlayGuard {
	public static volatile boolean verified;
	private static final Map<String,String> BODIES = Map.of(
		"renderBlockOverlays", "2824ec1fc8c70f755233d686b4c08cecef498d33cdc9b074712a902faeb05b8a",
		"renderBlockOverlay", "e811dbb942d3f59794675a73ab2f7eb42ea08469ca379c3601c8411a3a362c7f",
		"drawOverlayInternal", "62ca5bfef39d8e00800404b675c4c6bd3b830f7b49b1119900fd73091cb6e696");
	private LitematicaOverlayGuard() { }
	public static boolean verify(ClassNode target) {
		if (!target.name.equals("fi/dy/masa/litematica/render/schematic/WorldRendererSchematic")) return false;
		int count = 0;
		for (MethodNode method : target.methods) if (BODIES.containsKey(method.name)) {
			if (!fingerprint(method).equals(BODIES.get(method.name))) return false;
			count++;
		}
		return count == BODIES.size();
	}
	public static String fingerprint(MethodNode method) {
		var labels = new IdentityHashMap<LabelNode,Integer>(); int pc = 0;
		for (AbstractInsnNode n : method.instructions) {
			if (n instanceof LabelNode label) labels.put(label,pc);
			if (n.getOpcode() >= 0) pc++;
		}
		var text = new StringBuilder(method.desc).append('\n');
		for (AbstractInsnNode n : method.instructions) if (n.getOpcode() >= 0) {
			text.append(n.getOpcode()).append(':');
			if (n instanceof MethodInsnNode x) text.append(x.owner).append('/').append(x.name).append(x.desc).append(x.itf);
			else if (n instanceof FieldInsnNode x) text.append(x.owner).append('/').append(x.name).append(x.desc);
			else if (n instanceof TypeInsnNode x) text.append(x.desc);
			else if (n instanceof VarInsnNode x) text.append(x.var);
			else if (n instanceof IntInsnNode x) text.append(x.operand);
			else if (n instanceof IincInsnNode x) text.append(x.var).append('/').append(x.incr);
			else if (n instanceof LdcInsnNode x) text.append(x.cst.getClass().getName()).append('/').append(x.cst);
			else if (n instanceof JumpInsnNode x) text.append(labels.get(x.label));
			else if (n instanceof InvokeDynamicInsnNode x) text.append(x.name).append(x.desc).append(x.bsm).append(java.util.Arrays.toString(x.bsmArgs));
			else if (n instanceof MultiANewArrayInsnNode x) text.append(x.desc).append(x.dims);
			else if (n instanceof TableSwitchInsnNode || n instanceof LookupSwitchInsnNode) return "unsupported";
			text.append('\n');
		}
		for (var handler : method.tryCatchBlocks) text.append(labels.get(handler.start)).append(':').append(labels.get(handler.end))
			.append(':').append(labels.get(handler.handler)).append(':').append(handler.type).append('\n');
		try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.toString().getBytes(StandardCharsets.UTF_8))); }
		catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
	}
}
