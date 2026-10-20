package mcopt.metal.own;

import java.util.BitSet;
import java.util.function.IntPredicate;

/** Vertical coverage required before a whole chunk column can be handed off to near terrain. */
final class OwnSeamCoverage {
	private OwnSeamCoverage() {
	}

	static boolean coversSurface(int lowestSection, int highestSection, int cameraSection, int viewDistance) {
		// An empty surface interval (an all-air column) needs no geometry from either renderer.
		return lowestSection > highestSection
			|| (long) lowestSection >= (long) cameraSection - viewDistance
				&& (long) highestSection <= (long) cameraSection + viewDistance;
	}

	/**
	 * A buried or out-of-view section may never compile. It must not leave an approximate LOD pillar over a column whose
	 * visible terrain is already present. Keep the fallback if any missing section is actually needed by the graph, and
	 * until at least one required section has compiled (a newly loaded column is not handed off on an empty visible list).
	 */
	static boolean coversVisibleSections(boolean anyCompiled, BitSet missing, int firstSlot, int stride, IntPredicate visible) {
		if (!anyCompiled) return false;
		for (int y = missing.nextSetBit(0); y >= 0; y = missing.nextSetBit(y + 1)) {
			if (visible.test(firstSlot + y * stride)) return false;
		}
		return true;
	}
}
