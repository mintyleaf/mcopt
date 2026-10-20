package mcopt.metal.lod;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongArrays;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** Region headers only: absent chunks never enter the saved-import queue, and no region file is created or modified. */
final class LodSavedIndex {
	private LodSavedIndex() { }

	static long[] scan(Path directory, int cx, int cz, int radius, double fx, double fz) throws IOException {
		if (!Files.isDirectory(directory)) return new long[0];
		LongArrayList keys=new LongArrayList();
		ByteBuffer header=ByteBuffer.allocate(4096);
		try (var files=Files.newDirectoryStream(directory,"r.*.mca")) {
			for (Path file:files) {
				if (Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException("Saved index interrupted");
				String[] name=file.getFileName().toString().split("\\.");
				if (name.length!=4) continue;
				int rx,rz;
				try { rx=Integer.parseInt(name[1]);rz=Integer.parseInt(name[2]); }
				catch (NumberFormatException e) { continue; }
				long x0=(long)rx*32,z0=(long)rz*32;
				if (x0>cx+radius || x0+31<cx-radius || z0>cz+radius || z0+31<cz-radius) continue;
				header.clear();
				try (var input=FileChannel.open(file,StandardOpenOption.READ)) {
					while (header.hasRemaining() && input.read(header)>0) { }
				} catch (IOException e) { continue; }
				if (header.position()!=4096) continue;
				for (int i=0;i<1024;i++) {
					int location=header.getInt(i*4);
					if ((location>>>8)<2 || (location&255)==0) continue;
					int x=(int)x0+(i&31),z=(int)z0+(i>>5),dx=x-cx,dz=z-cz;
					if ((long)dx*dx+(long)dz*dz<=(long)radius*radius) keys.add(LodForest.chunkKey(x,z));
				}
			}
		}
		long[] result=keys.toLongArray();
		LongArrays.quickSort(result,(a,b)->Double.compare(
			LodRefinePlan.score((int)(a>>32)-cx,(int)a-cz,fx,fz),LodRefinePlan.score((int)(b>>32)-cx,(int)b-cz,fx,fz)));
		return result;
	}
}
