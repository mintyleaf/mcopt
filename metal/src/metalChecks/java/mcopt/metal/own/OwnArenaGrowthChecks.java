package mcopt.metal.own;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import mcopt.metal.MetalBridge;
import org.lwjgl.system.MemoryUtil;
import sun.misc.Unsafe;

/** Exercise production growth against real Metal buffers, including writes while the replacement is prepared. */
public final class OwnArenaGrowthChecks {
	private static void set(Object target, String name, Object value) throws Exception {
		Field f = OwnTerrain.class.getDeclaredField(name); f.setAccessible(true); f.set(target, value);
	}
	private static long value(Object target, String name) throws Exception {
		Field f = OwnTerrain.class.getDeclaredField(name); f.setAccessible(true); return f.getLong(target);
	}
	public static void main(String[] args) throws Exception {
		Field uf = Unsafe.class.getDeclaredField("theUnsafe"); uf.setAccessible(true);
		OwnTerrain terrain = (OwnTerrain) ((Unsafe) uf.get(null)).allocateInstance(OwnTerrain.class);
		long ctx = MetalBridge.createHeadlessContext();
		checkRenderReserve((Unsafe) uf.get(null), ctx);
		checkOptionalRefilter((Unsafe) uf.get(null), ctx);
		checkReservedGrowth((Unsafe) uf.get(null), ctx);
		int units = (8 << 20) / OwnTerrain.ARENA_QUAD;
		long buffer = OwnNative.buffer(ctx, (long) units * OwnTerrain.ARENA_QUAD, true);
		long address = OwnNative.contents(buffer);
		MemoryUtil.memSet(address, 0x3a, (long) units * OwnTerrain.ARENA_QUAD);
		var lock = new ReentrantReadWriteLock();
		var alloc = new OwnAlloc(units);
		if (alloc.alloc(units) != 0) throw new AssertionError("Could not fill fixture arena");
		var retired = new ConcurrentLinkedQueue<Long>();
		set(terrain, "ctx", ctx); set(terrain, "arenaLock", lock); set(terrain, "arenaGrowthLock", new Object());
		set(terrain, "quadAlloc", alloc); set(terrain, "arenaPhysicalUnits", units);
		set(terrain, "arenaBuffer", buffer); set(terrain, "arenaAddress", address); set(terrain, "releaseQueue", retired);
		Method grow = OwnTerrain.class.getDeclaredMethod("growArena", int.class); grow.setAccessible(true);
		Method allocate = OwnTerrain.class.getDeclaredMethod("allocLocked", int.class); allocate.setAccessible(true);
		var failure = new AtomicReference<Throwable>();
		Runnable growth = () -> { try { grow.invoke(terrain, 1); } catch (Throwable e) { failure.set(e); } };
		Thread first = new Thread(growth, "growth-one"), second = new Thread(growth, "growth-two");
		try {
			int before = OwnNative.liveBuffers();
			lock.readLock().lock();
			try {
				first.start(); second.start();
				long deadline = System.nanoTime() + 5_000_000_000L;
				while (System.nanoTime() < deadline && !(OwnNative.liveBuffers() == before + 1
					&& (first.getState() == Thread.State.WAITING || second.getState() == Thread.State.WAITING))) {
					if (failure.get() != null) throw new AssertionError(failure.get());
					Thread.sleep(1);
				}
				if (OwnNative.liveBuffers() != before + 1) throw new AssertionError("Preparation needs the writer lock or duplicate growers allocated buffers");
				if (value(terrain, "arenaBuffer") != buffer) throw new AssertionError("Arena was replaced while a writer still held it");
				// A late write must be included in the final protected copy, not lost to a speculative copy.
				MemoryUtil.memPutLong(address, 0x123456789abcdefL);
			} finally { lock.readLock().unlock(); }
			first.join(5000); second.join(5000);
			if (first.isAlive() || second.isAlive() || failure.get() != null) throw new AssertionError("Growth did not complete", failure.get());
			long latest = value(terrain, "arenaAddress");
			if (latest == address || MemoryUtil.memGetLong(latest) != 0x123456789abcdefL || retired.size() != 1)
				throw new AssertionError("Growth lost writes, moved indices or retired more than one buffer");
			for (long offset = 16384; offset < (long) units * OwnTerrain.ARENA_QUAD; offset += 16384)
				if (MemoryUtil.memGetByte(latest + offset) != 0x3a) throw new AssertionError("Copied arena prefix differs");
			if (MemoryUtil.memGetLong(address) != 0x123456789abcdefL) throw new AssertionError("In-flight old arena was released too early");
			record Written(int start, long stamp) { }
			var writes = new ConcurrentLinkedQueue<Written>();
			var workers = new ArrayList<Thread>();
			for (int worker = 0; worker < 4; worker++) {
				final int id = worker;
				Thread thread = new Thread(() -> {
					try {
						for (int i = 0; i < 256; i++) {
							int at = (int) allocate.invoke(terrain, 4);
							long stamp = (long) id << 32 | i;
							try { MemoryUtil.memPutLong(value(terrain, "arenaAddress") + (long) at * OwnTerrain.ARENA_QUAD, stamp); }
							finally { lock.readLock().unlock(); }
							writes.add(new Written(at, stamp));
						}
					} catch (Throwable e) { failure.set(e); }
				}, "arena-writer-" + worker);
				workers.add(thread); thread.start();
			}
			grow.invoke(terrain, units + 1);
			for (Thread thread : workers) { thread.join(5000); if (thread.isAlive()) throw new AssertionError("Writer deadlocked"); }
			if (failure.get() != null || writes.size() != 1024) throw new AssertionError("Concurrent writer failed", failure.get());
			latest = value(terrain, "arenaAddress");
			for (Written write : writes)
				if (MemoryUtil.memGetLong(latest + (long) write.start() * OwnTerrain.ARENA_QUAD) != write.stamp())
					throw new AssertionError("Concurrent writes were lost across growth");
			System.out.println("PASS: preparation proceeds while readers hold the old arena; growers serialize; late/concurrent writes, indices and old-buffer retirement survive real Metal growth");
		} finally {
			first.join(5000); second.join(5000);
			OwnNative.release(value(terrain, "arenaBuffer"));
			for (long old : retired) OwnNative.release(old);
		}
	}

	private static void checkReservedGrowth(Unsafe unsafe, long ctx) throws Exception {
		OwnTerrain terrain = (OwnTerrain) unsafe.allocateInstance(OwnTerrain.class);
		int logical = (8 << 20) / OwnTerrain.ARENA_QUAD, physical = logical * 4;
		long buffer = OwnNative.buffer(ctx, (long) physical * OwnTerrain.ARENA_QUAD, true), address = OwnNative.contents(buffer);
		var alloc = new OwnAlloc(logical); alloc.alloc(logical);
		var lock = new ReentrantReadWriteLock(); var retired = new ConcurrentLinkedQueue<Long>();
		set(terrain, "ctx", ctx); set(terrain, "quadAlloc", alloc); set(terrain, "arenaLock", lock);
		set(terrain, "arenaGrowthLock", new Object()); set(terrain, "arenaPhysicalUnits", physical);
		set(terrain, "arenaBuffer", buffer); set(terrain, "arenaAddress", address); set(terrain, "releaseQueue", retired);
		MemoryUtil.memPutLong(address, 0x123456789abcdefL);
		Method grow = OwnTerrain.class.getDeclaredMethod("growArena", int.class); grow.setAccessible(true);
		var result = new java.util.concurrent.FutureTask<Void>(() -> { grow.invoke(terrain, 1); return null; });
		Thread worker = new Thread(result, "reserved-capacity-grower");
		int before = OwnNative.liveBuffers();
		try {
			lock.readLock().lock();
			try {
				worker.start(); result.get(2, java.util.concurrent.TimeUnit.SECONDS);
				if (alloc.capacity() != logical * 2 || value(terrain, "arenaBuffer") != buffer
					|| value(terrain, "arenaAddress") != address || OwnNative.liveBuffers() != before || !retired.isEmpty()
					|| MemoryUtil.memGetLong(address) != 0x123456789abcdefL)
					throw new AssertionError("Reserved growth copied/replaced a buffer, waited for a reader or changed live geometry");
			} finally { lock.readLock().unlock(); }
			int tail = alloc.alloc(logical);
			if (tail != logical) throw new AssertionError("Reserved growth lost the old indices");
			MemoryUtil.memPutLong(address + (long) tail * OwnTerrain.ARENA_QUAD, 0xfedcba987654321L);
			grow.invoke(terrain, 1);
			if (alloc.capacity() != physical || value(terrain, "arenaBuffer") != buffer || OwnNative.liveBuffers() != before
				|| MemoryUtil.memGetLong(address + (long) tail * OwnTerrain.ARENA_QUAD) != 0xfedcba987654321L)
				throw new AssertionError("Second reserved growth moved data or allocated a replacement");
			System.out.println("PASS: both reserved growth steps finish without a writer lock, replacement allocation, copy or retirement; old/new geometry bytes and indices survive");
		} finally { worker.join(3000); OwnNative.release(buffer); }
	}

	private static void checkRenderReserve(Unsafe unsafe, long ctx) throws Exception {
		OwnTerrain terrain=(OwnTerrain)unsafe.allocateInstance(OwnTerrain.class);
		var alloc=new OwnAlloc(512); var lock=new ReentrantReadWriteLock();
		if(alloc.alloc(8)!=0) throw new AssertionError("Position-table prefix moved");
		long buffer=OwnNative.buffer(ctx,512L*OwnTerrain.ARENA_QUAD,true),address=OwnNative.contents(buffer);
		set(terrain,"quadAlloc",alloc);set(terrain,"arenaLock",lock);set(terrain,"arenaBuffer",buffer);set(terrain,"arenaAddress",address);
		Method allocate=OwnTerrain.class.getDeclaredMethod("allocLocked",int.class);allocate.setAccessible(true);
		Method sorted=OwnTerrain.class.getDeclaredMethod("sortedCopy",int.class,int.class,int.class);sorted.setAccessible(true);
		var held=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);
		Thread worker=new Thread(() -> { try (var ignored = alloc.locked()) {
			held.countDown();try{release.await(10,java.util.concurrent.TimeUnit.SECONDS);}catch(InterruptedException e){throw new AssertionError(e);}
		}},"reserved-arena-worker");
		var task=new java.util.concurrent.FutureTask<Void>(() -> {
			int source=(int)allocate.invoke(terrain,4);
			try { for(int q=0;q<4;q++) MemoryUtil.memSet(address+(long)(source+q)*OwnTerrain.ARENA_QUAD,q+1,OwnTerrain.ARENA_QUAD); }
			finally { lock.readLock().unlock(); }
			int perm=(int)allocate.invoke(terrain,1);
			try { for(int q=0;q<4;q++) MemoryUtil.memPutInt(address+(long)perm*OwnTerrain.ARENA_QUAD+q*4L,3-q); }
			finally { lock.readLock().unlock(); }
			int copy=(int)sorted.invoke(terrain,source,perm,4);
			for(int q=0;q<4;q++) for(int b=0;b<OwnTerrain.ARENA_QUAD;b++)
				if(MemoryUtil.memGetByte(address+(long)(copy+q)*OwnTerrain.ARENA_QUAD+b)!=(byte)(4-q)) throw new AssertionError("Sorted reserve geometry changed");
			alloc.free(source,4);alloc.free(perm,1);alloc.free(copy,4);
			return null;
		});
		Thread render=new Thread(task,"reserved-arena-render");alloc.reserveFor(render,128);
		int retiredShared=alloc.alloc(32);
		try {
			worker.start();if(!held.await(2,java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("Worker did not hold shared lock");
			render.start();task.get(2,java.util.concurrent.TimeUnit.SECONDS);
			// Retirement of a worker-owned range also avoids its monitor; used()/next allocation drain it later.
			alloc.free(retiredShared,32);
			if(release.getCount()!=1 || alloc.reserveUsed()!=0 || alloc.reserveHits()!=3) throw new AssertionError("Production allocation/sort waited for shared allocator");
		} finally { release.countDown();worker.join(3000);render.join(3000);OwnNative.release(buffer); }
		if(alloc.used()!=8) throw new AssertionError("Sorted reserve leaked arena units");
		System.out.println("PASS: production allocLocked and sortedCopy preserve every quad byte and finish while a worker holds the shared allocator lock");
	}

	private static void checkOptionalRefilter(Unsafe unsafe,long ctx) throws Exception {
		OwnTerrain terrain=(OwnTerrain)unsafe.allocateInstance(OwnTerrain.class);
		var alloc=new OwnAlloc(512);var lock=new ReentrantReadWriteLock();
		int source=alloc.alloc(4),perm=alloc.alloc(1);
		long buffer=OwnNative.buffer(ctx,512L*OwnTerrain.ARENA_QUAD,true),address=OwnNative.contents(buffer);
		long records=OwnNative.buffer(ctx,64L*32,true),recordAddress=OwnNative.contents(records),master=MemoryUtil.nmemCalloc(1,256);
		set(terrain,"ctx",ctx);set(terrain,"quadAlloc",alloc);set(terrain,"arenaLock",lock);set(terrain,"arenaBuffer",buffer);set(terrain,"arenaAddress",address);
		set(terrain,"recBuffer",records);set(terrain,"recAddress",recordAddress);set(terrain,"recAlloc",new OwnAlloc(64));
		set(terrain,"deferred",new java.util.ArrayDeque<>());set(terrain,"master",master);
		for(int q=0;q<4;q++) {
			MemoryUtil.memSet(address+(long)(source+q)*OwnTerrain.ARENA_QUAD,q+1,OwnTerrain.ARENA_QUAD);
			MemoryUtil.memPutInt(address+(long)perm*OwnTerrain.ARENA_QUAD+q*4L,3-q);
		}
		var mesh=new OwnTerrain.OwnMesh();var layer=new OwnTerrain.Layer(source,4,new int[0],-1);
		mesh.layers[2]=layer;mesh.permStart=perm;mesh.permUnits=1;mesh.tRecCount=1;
		mesh.tFace=new byte[]{0,1,0,1};mesh.tPlane=new float[]{1,1,1,1};mesh.tBox=new float[]{0,0,0,16,16,16};
		Method refilter=OwnTerrain.class.getDeclaredMethod("refilter",OwnTerrain.OwnMesh.class,OwnTerrain.Layer.class,int.class,double.class,double.class,double.class);refilter.setAccessible(true);
		Method tcull=OwnTerrain.class.getDeclaredMethod("tcull",OwnTerrain.OwnMesh.class,int.class,double.class,double.class,double.class);tcull.setAccessible(true);
		var held=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);
		Thread worker=new Thread(() -> {try(var ignored=alloc.locked()) {
			held.countDown();try{release.await(10,java.util.concurrent.TimeUnit.SECONDS);}catch(InterruptedException e){throw new AssertionError(e);}
		}},"refilter-blocked-worker");
		var task=new java.util.concurrent.FutureTask<Void>(() -> {
			int fill=alloc.alloc(4); // exhaust the private reserve without touching the held shared lock
			refilter.invoke(terrain,mesh,layer,0,0d,0d,0d);
			if(mesh.fPerm!=-1 || mesh.fRec!=-1 || mesh.fFor!=-1 || mesh.fSort!=-1) throw new AssertionError("Deferred refilter published a partial result");
			if((int)tcull.invoke(terrain,mesh,0,0d,0d,0d)!=mesh.tRecCount || mesh.tUse) throw new AssertionError("Deferred filter did not select the full current order");
			for(int q=0;q<4;q++) if(MemoryUtil.memGetInt(address+(long)perm*OwnTerrain.ARENA_QUAD+q*4L)!=3-q) throw new AssertionError("Fallback changed the full sort order");
			alloc.free(fill,4);return null;
		});
		Thread render=new Thread(task,"refilter-render");alloc.reserveFor(render,4);
		try {
			worker.start();if(!held.await(2,java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("Worker lock missing");
			render.start();task.get(2,java.util.concurrent.TimeUnit.SECONDS);
			if(release.getCount()!=1 || !worker.isAlive()) throw new AssertionError("Refilter waited for the worker");
			release.countDown();worker.join(3000);
			refilter.invoke(terrain,mesh,layer,0,0d,0d,0d);
			boolean sorted=Boolean.getBoolean("mcopt.own.mesh.tsorted");
			if(mesh.fRec<0 || mesh.fPerm<0 || mesh.fKept!=2 || sorted && mesh.fSort<0) throw new AssertionError("Deferred filter did not retry successfully");
			for(int q=0;q<2;q++) {
				if(MemoryUtil.memGetInt(address+(long)mesh.fPerm*OwnTerrain.ARENA_QUAD+q*4L)!=(q==0?3:1)) throw new AssertionError("Filtered permutation changed");
				if(sorted) for(int b=0;b<OwnTerrain.ARENA_QUAD;b++)
					if(MemoryUtil.memGetByte(address+(long)(mesh.fSort+q)*OwnTerrain.ARENA_QUAD+b)!=(byte)(q==0?4:2)) throw new AssertionError("Filtered order changed quad bytes");
			}
			if((int)tcull.invoke(terrain,mesh,0,0d,0d,0d)!=1 || !mesh.tUse) throw new AssertionError("Completed filter not selected");
			System.out.println("PASS: exhausted-reserve refilter never waits for held worker lock; full current order remains intact; retry publishes identical kept quad bytes");
		} finally {release.countDown();worker.join(3000);render.join(3000);OwnNative.release(buffer);OwnNative.release(records);MemoryUtil.nmemFree(master);}
	}
}
