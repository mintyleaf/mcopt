package mcopt.metal.compat;

import org.objectweb.asm.tree.*;

/** Only the inspected Indigo method may bypass its state-only culling hook. */
public final class MoreCullingIndigoGuard {
	public static volatile boolean verified;
	private static final String OWNER = "net/fabricmc/fabric/impl/client/indigo/renderer/render/AltModelBlockRendererImpl";
	private static final String BODY = "ae335cf210a33b3e75c5f170a947abd15ee8247f5e58dcb02163520ee99103b2";
	private MoreCullingIndigoGuard() { }

	public static boolean verify(ClassNode target) {
		if (!target.name.equals(OWNER)) return false;
		for (String[] field : new String[][] {{"level", "Lnet/minecraft/client/renderer/block/BlockAndTintGetter;"},
			{"pos", "Lnet/minecraft/core/BlockPos;"}, {"scratchPos", "Lnet/minecraft/core/BlockPos$MutableBlockPos;"}})
			if (target.fields.stream().noneMatch(f -> f.name.equals(field[0]) && f.desc.equals(field[1]))) return false;
		int count = 0;
		for (MethodNode method : target.methods) if (method.name.equals("shouldCullFace") && method.desc.equals("(Lnet/minecraft/core/Direction;)Z")) {
			if (!LitematicaOverlayGuard.fingerprint(method).equals(BODY)) return false;
			count++;
		}
		return count == 1;
	}
}
