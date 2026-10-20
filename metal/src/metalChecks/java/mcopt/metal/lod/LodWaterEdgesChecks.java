package mcopt.metal.lod;

public final class LodWaterEdgesChecks {
	public static void main(String[] args) {
		LodWaterEdges field = new LodWaterEdges();
		int[] mask = new int[9];
		java.util.Arrays.fill(mask, 31);
		float[] d = field.update(mask, -20, -10, 9, 1);
		equal(8, d[4 * 9 + 4]);
		equal(24, d[4 * 9 + 3]);
		equal(40, d[4 * 9 + 2]);
		equal(-8, d[4 * 9 + 5]);
		equal(-24, d[4 * 9 + 6]);
		if (field.update(mask, -20, -10, 9, 1) != d) throw new AssertionError("Unchanged mask rebuilt the field");
		mask[4] &= ~(1 << 2);
		d = field.update(mask, -20, -10, 9, 1);
		equal(-8, d[4 * 9 + 2]);
		equal(8, d[4 * 9 + 3]);
		int[] wide = new int[65 * 3];
		for (int z = 0; z < 65; z++) { wide[z * 3] = -1; wide[z * 3 + 1] = 1; }
		d = field.update(wide, -100, -100, 65, 3);
		equal(8, d[32 * 65 + 32]);
		equal(-8, d[32 * 65 + 33]);
		equal(24, d[32 * 65 + 31]);
		System.out.println("PASS: water edge field follows coverage borders, streaming holes, negative origins and word boundaries; unchanged masks reuse cached distances");
	}

	private static void equal(float expected, float actual) {
		if (expected != actual) throw new AssertionError("Expected " + expected + ", got " + actual);
	}
}
