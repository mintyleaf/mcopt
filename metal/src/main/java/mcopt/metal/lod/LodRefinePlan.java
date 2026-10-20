package mcopt.metal.lod;

import java.util.Arrays;

/** Small precomputed, direction-biased rings: no sorting or unbounded task queue on the server thread. */
final class LodRefinePlan {
	record Offset(int x, int z) { }
	private final Offset[][] directions = new Offset[8][];
	private int x = Integer.MIN_VALUE, z, direction, cursor, exclusion;

	LodRefinePlan(int radius) {
		var offsets = new java.util.ArrayList<Offset>();
		for (int z=-radius;z<=radius;z++) for (int x=-radius;x<=radius;x++) if (x*x+z*z<=radius*radius) offsets.add(new Offset(x,z));
		for (int d=0;d<8;d++) {
			double fx=Math.cos(d*Math.PI/4), fz=Math.sin(d*Math.PI/4);
			Offset[] order=offsets.toArray(Offset[]::new);
			Arrays.sort(order,java.util.Comparator.comparingDouble(p -> score(p.x,p.z,fx,fz)));
			this.directions[d]=order;
		}
	}

	static double score(double dx, double dz, double fx, double fz) {
		return Math.hypot(dx,dz)-0.6*(dx*fx+dz*fz);
	}

	Offset next(int x, int z, double fx, double fz) {
		return this.next(x, z, fx, fz, -1);
	}

	Offset next(int x, int z, double fx, double fz, int exclusion) {
		int d=Math.floorMod((int)Math.round(Math.atan2(fz,fx)*4/Math.PI),8);
		if (x!=this.x || z!=this.z || d!=this.direction || exclusion!=this.exclusion) {
			this.x=x;this.z=z;this.direction=d;this.exclusion=exclusion;this.cursor=0;
		}
		Offset[] order=this.directions[d];
		// Skip the normal render region here, so flight cannot keep resetting the bounded scan inside that region.
		while (this.cursor < order.length) {
			Offset p = order[this.cursor++];
			if (Math.abs(p.x) > exclusion || Math.abs(p.z) > exclusion) return p;
		}
		this.cursor=0;
		return null;
	}
}
