package mcopt.metal.lod;

public final class LodEnableChecks {
	public static void main(String[] args) {
		boolean expected = Boolean.parseBoolean(args[0]);
		if (LodConfig.ENABLED != expected || (mcopt.metal.own.OwnViewDistance.verticalDistance(12) == Integer.MAX_VALUE) != expected)
			throw new AssertionError("LOD enable state disagrees with explicit off/radius-only setting");
		System.out.println("PASS: " + (expected ? "radius alone enables LODs" : "explicit off disables LODs even when a radius is saved"));
	}
}
