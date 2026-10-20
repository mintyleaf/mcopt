package mcopt.metal.own;

/** LODs extend the fog beyond vanilla's vertical cutoff, exposing holes unless near columns keep their full height. */
public final class OwnViewDistance {
	private static final boolean FULL_HEIGHT = Boolean.parseBoolean(System.getProperty("mcopt.lod", System.getProperty("mcopt.lod.radius") != null ? "true" : "false"));

	private OwnViewDistance() {
	}

	public static int verticalDistance(int viewDistance) {
		return FULL_HEIGHT ? Integer.MAX_VALUE : viewDistance;
	}
}
