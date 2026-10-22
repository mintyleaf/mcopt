package mcopt.metal.own;

/** LODs extend the fog beyond vanilla's vertical cutoff, exposing holes unless near columns keep their full height. */
public final class OwnViewDistance {

	private OwnViewDistance() {
	}

	public static int verticalDistance(int viewDistance) {
		return mcopt.metal.config.McoptRuntime.current().flag("mcopt.lod") ? Integer.MAX_VALUE : viewDistance;
	}
}
