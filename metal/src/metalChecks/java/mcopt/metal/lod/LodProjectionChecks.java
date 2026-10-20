package mcopt.metal.lod;

import org.joml.FrustumIntersection;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.system.MemoryUtil;

/** Projection regression using walking phases and terrain points on both sides of the near/LOD boundary. */
public final class LodProjectionChecks {
	private static void require(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}

	public static void main(String[] args) {
		Matrix4f base = new Matrix4f().setPerspective((float) Math.toRadians(70), 16.0F / 9, 0.05F, 8192, true);
		Matrix4f view = new Matrix4f().rotateX(0.13F).rotateY(-0.42F);
		Matrix4f baseCopy = new Matrix4f(base), viewCopy = new Matrix4f(view);
		Matrix4f actual = new Matrix4f(), unbobbed = new Matrix4f(base).mul(view);
		long storage = MemoryUtil.nmemAlloc(256);
		try {
			// Uniform rings use nonzero slice offsets. Leave a different matrix at the buffer's base to catch offset mistakes.
			new Matrix4f().scaling(3).getToAddress(storage);
			long projection = storage + 128;
			float largestBobbingShift = 0;
			for (int frame = 0; frame < 48; frame++) {
				float phase = (float) (frame * Math.PI / 12);
				// Projection * camera effects * view rotation, as GameRenderer composes the world's transform.
				Matrix4f rendered = new Matrix4f(base).translate((float) Math.sin(phase) * 0.08F,
					-(float) Math.abs(Math.cos(phase)) * 0.1F, 0).rotateZ((float) Math.sin(phase) * 0.025F)
					.rotateX((float) Math.cos(phase) * 0.015F);
				rendered.getToAddress(projection);
				LodProjection.viewProjection(actual, base, view, projection);
				Matrix4f near = rendered.mul(view);
				FrustumIntersection nearFrustum = new FrustumIntersection(near), lodFrustum = new FrustumIntersection(actual);
				for (float distance : new float[] {128, 192, 256, 512, 2048}) {
					for (float x : new float[] {-1, -0.5F, 0, 0.5F, 1}) {
						Vector3f world = new Vector3f(x * distance, -distance * 0.1F, -distance);
						Vector3f a = near.transformProject(world, new Vector3f());
						Vector3f b = actual.transformProject(world, new Vector3f());
						require(a.distance(b) < 1e-6F, "Near and LOD terrain diverged during camera bobbing");
						require(nearFrustum.testPoint(world.x, world.y, world.z) == lodFrustum.testPoint(world.x, world.y, world.z),
							"LOD culling used a different camera transform");
						largestBobbingShift = Math.max(largestBobbingShift, b.distance(unbobbed.transformProject(world, new Vector3f())));
					}
				}
			}
			require(largestBobbingShift > 0.01F, "Bobbing test did not exercise a visible screen-space shift");
			base.getToAddress(projection);
			LodProjection.viewProjection(actual, base, view, projection);
			require(actual.equals(unbobbed, 1e-6F), "Bobbing disabled changed the terrain transform");
			LodProjection.viewProjection(actual, base, view, 0);
			require(actual.equals(unbobbed, 1e-6F), "Missing projection buffer did not fall back to the camera");
			require(base.equals(baseCopy) && view.equals(viewCopy), "LOD changed the camera's matrices");
			System.out.println("PASS: near/LOD projection and culling agree across 48 bobbing phases, uniform offsets and camera fallback");
		} finally {
			MemoryUtil.nmemFree(storage);
		}
	}
}
