package mcopt.metal.lod;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.lwjgl.system.MemoryUtil;

/** Publication can stop completely without allowing completed native storage to grow. */
public final class LodMeshResultChecks {
	public static void main(String[] args) throws Exception {
		var results=new LodMeshResults(4096,2);
		var failure=new AtomicReference<Throwable>();
		try {
			for(int key=0;key<2;key++){results.reserve(2048);results.offer(make(results,key,1));}
			var entered=new CountDownLatch(1);var done=new CountDownLatch(1);
			Thread worker=Thread.ofPlatform().start(()->{
				try {entered.countDown();if(!results.reserve(2048))throw new AssertionError("Closed early");results.offer(make(results,1,2));}
				catch(Throwable t){failure.set(t);}finally{done.countDown();}
			});
			entered.await();
			if(done.await(100,TimeUnit.MILLISECONDS) || results.bytes()!=4096)throw new AssertionError("Unbounded completed allocations");
			var held=results.poll();
			if(results.bytes()!=4096 || done.await(100,TimeUnit.MILLISECONDS))throw new AssertionError("Deferred publication lost its credits");
			held.close();
			if(!done.await(5,TimeUnit.SECONDS) || failure.get()!=null)throw new AssertionError("Worker did not resume",failure.get());
			worker.join();
			if(results.bytes()!=2048 || results.size()!=1 || results.reservations()!=1)throw new AssertionError("Superseded result leaked storage");
			var deferred=results.poll();results.offer(deferred);
			results.reserve(2048);results.offer(make(results,1,1)); // obsolete completion must lose to version 2
			var latest=results.poll();
			if(latest.data[1]!=2)throw new AssertionError("Old completion replaced latest");latest.close();
			for(int key=0;key<2;key++){results.reserve(2048);results.offer(make(results,key,3));}
			var closing=new CountDownLatch(1);
			Thread waiter=Thread.ofPlatform().start(()->{
				try{if(results.reserve(2048))failure.set(new AssertionError("Close admitted another allocation"));}
				catch(Throwable t){failure.set(t);}finally{closing.countDown();}
			});
			results.close();
			if(!closing.await(5,TimeUnit.SECONDS) || failure.get()!=null)throw new AssertionError("Close held credit waiter",failure.get());waiter.join();
			if(results.bytes()!=0 || results.reservations()!=0)throw new AssertionError("Close leaked native credits");
		} finally { results.close(); }
		System.out.println("PASS: stopped/deferred publication holds byte and count credits, workers resume, latest completion wins and close drains storage/waiters");
	}
	private static LodMeshResults.Result make(LodMeshResults owner,long key,long sequence) {
		return owner.new Result(new long[]{key,sequence,MemoryUtil.nmemAlloc(2048),512,0,0},null);
	}
}
