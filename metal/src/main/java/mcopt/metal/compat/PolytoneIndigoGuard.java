package mcopt.metal.compat;

import org.objectweb.asm.tree.ClassNode;

/** Offset injection is limited to the inspected Indigo tessellator. */
public final class PolytoneIndigoGuard {
	public static volatile boolean verified;
	private PolytoneIndigoGuard() { }
	public static boolean verify(ClassNode target) {
		if (!target.name.equals("net/fabricmc/fabric/impl/client/indigo/renderer/render/AltModelBlockRendererImpl")) return false;
		var methods = target.methods.stream().filter(m -> m.name.equals("tesselateBlock")).toList();
		return methods.size() == 1 && LitematicaOverlayGuard.fingerprint(methods.getFirst()).equals("fb3f49a4309ead4c300a193981f9927317c3bcd04a979553d0765e010df5b319");
	}
}
