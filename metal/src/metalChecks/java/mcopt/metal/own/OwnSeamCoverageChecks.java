package mcopt.metal.own;

import java.util.BitSet;

/** Regression cases for ascending past the near renderer's vertical range. */
public final class OwnSeamCoverageChecks {
	public static void main(String[] args) {
		// A sloping surface spanning sections 3..6 at RD 12. Every section remains compiled while flying upward.
		// Below the cutoff the near renderer covers the whole column. Afterward the LOD must cover its missing layers.
		for (int cameraY = -256; cameraY <= 1024; cameraY++) {
			int cameraSection = cameraY >> 4;
			boolean[] nearLayers = new boolean[4];
			boolean allNear = true;
			for (int layer = 0; layer < nearLayers.length; layer++) {
				nearLayers[layer] = Math.abs(3 + layer - cameraSection) <= 12;
				allNear &= nearLayers[layer];
			}
			boolean lodHidden = OwnSeamCoverage.coversSurface(3, 6, cameraSection, 12);
			if (lodHidden != allNear) throw new AssertionError("Wrong handoff at camera Y=" + cameraY);
			for (boolean near : nearLayers) {
				if (!near && lodHidden) throw new AssertionError("Uncovered surface layer at camera Y=" + cameraY);
			}
		}
		check(true, 4, 4, 16, 12);   // Inclusive boundary: sea surface still inside the near range.
		check(false, 4, 4, 17, 12);  // Entire surface below the range: an empty intersection is not coverage.
		check(false, 3, 6, 16, 12);  // Only part of the surface is inside the range.
		check(false, 3, 6, -7, 12);  // Same failure while descending, above the camera.
		check(true, -4, -1, 8, 12);  // Negative section coordinates and inclusive boundary.
		check(false, -4, -1, 9, 12);
		check(true, -4, -5, 100, 12); // All-air column, no surface within the world's bounds.
		boolean fullHeight = Boolean.getBoolean("mcopt.lod") || System.getProperty("mcopt.lod.radius") != null;
		int verticalDistance = OwnViewDistance.verticalDistance(12);
		if (verticalDistance != (fullHeight ? Integer.MAX_VALUE : 12)) throw new AssertionError("Wrong vertical distance mode");
		for (int cameraY = -1024; cameraY <= 2048; cameraY++) {
			if (fullHeight && !OwnSeamCoverage.coversSurface(-4, 19, cameraY >> 4, verticalDistance)) {
				throw new AssertionError("LOD mode lost full-height coverage at Y=" + cameraY);
			}
		}
		System.out.println("LOD handoff: no uncovered surface layers across 1,281 altitudes; partial, empty, negative and boundary ranges passed");
		System.out.println("Near terrain vertical range: " + (fullHeight ? "full height with LODs" : "vanilla without LODs"));
		checkStreaming();
	}

	private static void checkStreaming() {
		// A sloping/ocean chunk spans sections 3..8. A buried section and the out-of-view treetop never compile, while
		// ground, water and seabed do. Requiring the entire height interval left permanent LOD pillars over this chunk.
		BitSet missing = new BitSet(), visible = new BitSet();
		int firstSlot = 73, stride = 21;
		missing.set(3);
		missing.set(8);
		visible.set(firstSlot + 5 * stride);
		streamCheck(true, true, missing, firstSlot, stride, visible, "buried and out-of-view sections");
		streamCheck(false, false, missing, firstSlot, stride, visible, "new chunk without any compiled surface");
		// Turning toward an unbuilt upper section must return LOD coverage immediately, without a mesh event.
		visible.set(firstSlot + 8 * stride);
		streamCheck(false, true, missing, firstSlot, stride, visible, "look toward unbuilt canopy");
		missing.clear(8);
		streamCheck(true, true, missing, firstSlot, stride, visible, "canopy arrives; buried section stays unbuilt");
		// A visible seabed still waiting to compile must keep the fallback, preventing sky through transparent water.
		visible.set(firstSlot + 3 * stride);
		streamCheck(false, true, missing, firstSlot, stride, visible, "visible missing seabed");
		missing.clear(3);
		streamCheck(true, true, missing, firstSlot, stride, visible, "seabed arrives");
		// Custom world heights and negative-coordinate grid columns must not alias section bits at 32 or 64.
		for (int section : new int[] {0, 31, 32, 63, 64, 95, 127}) {
			missing.clear();
			visible.clear();
			missing.set(section);
			visible.set(firstSlot + section * stride);
			streamCheck(false, true, missing, firstSlot, stride, visible, "tall world section " + section);
		}
		// The real visibility journal must change for rotation, additions, and F3+A / world reload even at the same slots.
		OwnVisible.cleared();
		long before = OwnVisible.revision();
		OwnVisible.added(100, 0);
		if (!OwnVisible.isVisible(100) || OwnVisible.revision() == before) throw new AssertionError("Missing visible addition");
		OwnVisible.cleared();
		if (OwnVisible.isVisible(100)) throw new AssertionError("Rotation retained an old visible slot");
		OwnVisible.added(100, 0);
		before = OwnVisible.revision();
		OwnVisible.levelReset();
		if (OwnVisible.isVisible(100) || OwnVisible.revision() == before) throw new AssertionError("Reload retained visible coverage");
		System.out.println("LOD streaming handoff: buried sections, canopy rotation, pending seabed, tall worlds and visibility reload passed");
	}

	private static void streamCheck(boolean expected, boolean anyCompiled, BitSet missing, int first, int stride, BitSet visible, String label) {
		if (OwnSeamCoverage.coversVisibleSections(anyCompiled, missing, first, stride, visible::get) != expected) {
			throw new AssertionError("Wrong streaming handoff: " + label);
		}
	}

	private static void check(boolean expected, int lo, int hi, int camera, int distance) {
		if (OwnSeamCoverage.coversSurface(lo, hi, camera, distance) != expected) {
			throw new AssertionError("Unexpected coverage for " + lo + ".." + hi + " from " + camera);
		}
	}
}
