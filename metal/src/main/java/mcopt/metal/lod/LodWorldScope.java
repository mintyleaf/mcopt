package mcopt.metal.lod;

/** Level identity matters even across two client instances of the same dimension or save. */
final class LodWorldScope {
	private LodWorldScope() { }
	static boolean accepts(Object owner, Object current, Object source) {
		return owner != null && owner == current && owner == source;
	}
}
