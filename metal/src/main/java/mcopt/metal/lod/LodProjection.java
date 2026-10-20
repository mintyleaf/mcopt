package mcopt.metal.lod;

import org.joml.Matrix4f;
import org.joml.Matrix4fc;

/** The camera-relative transform shared by far-terrain drawing, culling and reprojection. */
final class LodProjection {
	private LodProjection() {
	}

	/** renderedProjection points at the first matrix in the active projection slice; 0 falls back to the camera. */
	static void viewProjection(Matrix4f target, Matrix4fc cameraProjection, Matrix4fc viewRotation, long renderedProjection) {
		if (renderedProjection != 0) target.setFromAddress(renderedProjection);
		else target.set(cameraProjection);
		target.mul(viewRotation);
	}
}
