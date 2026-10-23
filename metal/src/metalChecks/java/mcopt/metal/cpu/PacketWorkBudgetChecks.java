package mcopt.metal.cpu;

import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

/** FIFO/progress, client-server thread isolation, nesting, disabling and exceptional cleanup. */
public final class PacketWorkBudgetChecks {
    public static void main(String[] args)throws Exception {
        var queue=new ArrayDeque<Integer>();for(int i=0;i<100;i++)queue.add(i);
        var output=new ArrayList<Integer>();long now=100;
        while(!queue.isEmpty()) {
            var scope=PacketWorkBudget.begin(now,20);int count=0;
            try {
                check(!PacketWorkBudget.exhaustedAt(false,now+1000),"First packet must progress even after a long pause");
                while(!PacketWorkBudget.exhaustedAt(queue.isEmpty(),now)) {
                    output.add(queue.remove());PacketWorkBudget.handled();count++;now+=9;
                }
                check(count>0 && count<=3,"Budget must yield at packet boundaries");
            }finally{PacketWorkBudget.end(scope);}
        }
        check(PacketWorkBudget.yieldedFrames()>0,"Yield counter did not record work");
        check(output.equals(java.util.stream.IntStream.range(0,100).boxed().toList()),"Packets lost, reordered or duplicated");
        check(PacketWorkBudget.begin(100,0)==null,"Off switch created a budget");
        check(!PacketWorkBudget.exhaustedAt(false,Long.MAX_VALUE),"Unscoped queue was limited");
        var outer=PacketWorkBudget.begin(100,20);
        try {
            PacketWorkBudget.handled();var nested=PacketWorkBudget.begin(110,200);check(nested==null,"Nested scope replaced deadline");PacketWorkBudget.end(nested);
            check(PacketWorkBudget.exhaustedAt(false,120),"Nested exit cleared outer scope");
            var failure=new AtomicReference<Throwable>();
            var server=new Thread(()->{try{PacketWorkBudget.handled();check(!PacketWorkBudget.exhaustedAt(false,1000),"Client budget leaked to server thread");}catch(Throwable t){failure.set(t);}});
            server.start();server.join();if(failure.get()!=null)throw new AssertionError(failure.get());
            check(outer.handled==1,"Server work modified client count");
        }finally{PacketWorkBudget.end(outer);}
        var scope=PacketWorkBudget.begin(100,20);
        try{throw new IllegalStateException("fixture");}catch(IllegalStateException expected){}finally{PacketWorkBudget.end(scope);}
        check(!PacketWorkBudget.exhaustedAt(false,1000),"Exception retained budget");
        check(PacketWorkBudget.exhaustedAt(true,1000),"Empty queue must stop");
        System.out.println("PASS: FIFO/progress, packet boundaries, off/unscoped behavior, nested scopes, server isolation and exceptional cleanup");
    }
    private static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);}
}
