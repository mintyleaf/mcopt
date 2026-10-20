package mcopt.metal.lod;

/** Population work limits, separate from renderer optimization profiles and estimated-terrain workers. */
enum LodWorkload {
	LOW(1, 1, 1, 1_000_000L), BALANCED(2, 2, 2, 2_000_000L), HIGH(4, 3, 3, 3_000_000L);
	final int full, probes, imports;
	final long tickBudgetNanos;
	LodWorkload(int full, int probes, int imports, long tickBudgetNanos) {
		this.full = full; this.probes = probes; this.imports = imports; this.tickBudgetNanos = tickBudgetNanos;
	}
	static LodWorkload parse(String value) {
		try { return valueOf(value.toUpperCase(java.util.Locale.ROOT)); }
		catch (IllegalArgumentException ignored) { return BALANCED; }
	}
	String label() { return name().toLowerCase(java.util.Locale.ROOT); }
}
