package mcopt.metal.lod;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public final class LodPredictionCacheChecks {
	public static void main(String[] args) throws Exception {
		var cache=new LodPredictionCache(1024,8);
		for(int n=0;n<100_000;n++) {
			cache.offer(0,n,new byte[128],128);
			cache.get(1,n,()->new byte[256],v->v.length);
			if(cache.bytes()>1024 || cache.size()>8) throw new AssertionError("Insertion bypasses budget");
		}
		cache.get(2,0,()->new byte[2048],v->v.length);
		if(cache.bytes()>1024 || cache.contains(2,0)) throw new AssertionError("Oversized feature retained");
		var lru=new LodPredictionCache(3,3);
		for(int n=0;n<3;n++)lru.offer(0,n,n,1);
		lru.get(0,0,()->{throw new AssertionError("Hot entry cleared");},v->1);
		lru.offer(1,3,3,1);
		if(lru.contains(0,1) || !lru.contains(0,0) || lru.size()!=3) throw new AssertionError("Eviction cleared hot entries/domain collision");
		var started=new CountDownLatch(1);var release=new CountDownLatch(1);var calls=new AtomicInteger();
		try(var pool=Executors.newFixedThreadPool(8)) {
			var results=new java.util.ArrayList<Future<byte[]>>();
			for(int n=0;n<8;n++)results.add(pool.submit(()->cache.get(1,-1,()->{
				calls.incrementAndGet();started.countDown();try{release.await();}catch(InterruptedException e){throw new RuntimeException(e);}return new byte[100];
			},v->v.length)));
			if(!started.await(5,TimeUnit.SECONDS))throw new AssertionError("Miss not started");
			release.countDown();var first=results.getFirst().get(5,TimeUnit.SECONDS);
			for(var f:results)if(f.get(5,TimeUnit.SECONDS)!=first)throw new AssertionError("Duplicate same-key computation");
			if(calls.get()!=1 || cache.computing()!=0)throw new AssertionError("Computation lease leaked");
		}
		try{cache.get(0,-2,()->{throw new IllegalStateException("expected");},v->1);throw new AssertionError("Failure hidden");}catch(IllegalStateException expected){}
		if(cache.get(0,-2,()->17,v->1)!=17 || cache.computing()!=0)throw new AssertionError("Failed miss poisoned cache");
		System.out.println("PASS: 200,000 ground/feature insertions obey shared byte/count budget, incremental LRU preserves hot data, eight misses coalesce and failures release leases");
	}
}
