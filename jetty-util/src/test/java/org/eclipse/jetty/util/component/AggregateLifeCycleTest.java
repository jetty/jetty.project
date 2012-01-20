package org.eclipse.jetty.util.component;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;

import junit.framework.Assert;

import org.eclipse.jetty.util.TypeUtil;
import org.junit.Test;


public class AggregateLifeCycleTest
{

    @Test
    public void testStartStopDestroy() throws Exception
    {
        final AtomicInteger destroyed=new AtomicInteger();
        final AtomicInteger started=new AtomicInteger();
        final AtomicInteger stopped=new AtomicInteger();

        AggregateLifeCycle a0=new AggregateLifeCycle();
        
        AggregateLifeCycle a1=new AggregateLifeCycle()
        {
            @Override
            protected void doStart() throws Exception
            {
                started.incrementAndGet();
                super.doStart();
            }

            @Override
            protected void doStop() throws Exception
            {
                stopped.incrementAndGet();
                super.doStop();
            }
            
            @Override
            public void destroy()
            {
                destroyed.incrementAndGet();
                super.destroy();
            }

        };
        
        
        a0.addBean(a1);
        
        a0.start();
        Assert.assertEquals(1,started.get());
        Assert.assertEquals(0,stopped.get());
        Assert.assertEquals(0,destroyed.get());
        
        a0.start();
        Assert.assertEquals(1,started.get());
        Assert.assertEquals(0,stopped.get());
        Assert.assertEquals(0,destroyed.get());
        
        a0.stop();
        Assert.assertEquals(1,started.get());
        Assert.assertEquals(1,stopped.get());
        Assert.assertEquals(0,destroyed.get());
        
        a0.start();
        Assert.assertEquals(2,started.get());
        Assert.assertEquals(1,stopped.get());
        Assert.assertEquals(0,destroyed.get());
        
        a0.stop();
        Assert.assertEquals(2,started.get());
        Assert.assertEquals(2,stopped.get());
        Assert.assertEquals(0,destroyed.get());
        
        a0.destroy();
        
        Assert.assertEquals(2,started.get());
        Assert.assertEquals(2,stopped.get());
        Assert.assertEquals(1,destroyed.get());
        
        a0.start();
        Assert.assertEquals(2,started.get());
        Assert.assertEquals(2,stopped.get());
        Assert.assertEquals(1,destroyed.get());
        
        a0.addBean(a1);
        a0.start();
        Assert.assertEquals(3,started.get());
        Assert.assertEquals(2,stopped.get());
        Assert.assertEquals(1,destroyed.get());
        
        a0.removeBean(a1);
        a0.stop();
        a0.destroy();
        Assert.assertEquals(3,started.get());
        Assert.assertEquals(2,stopped.get());
        Assert.assertEquals(1,destroyed.get());
        
        a1.stop();
        Assert.assertEquals(3,started.get());
        Assert.assertEquals(3,stopped.get());
        Assert.assertEquals(1,destroyed.get());
        
        a1.destroy();
        Assert.assertEquals(3,started.get());
        Assert.assertEquals(3,stopped.get());
        Assert.assertEquals(2,destroyed.get());
        
    }
    
    @Test
    public void testDisJoint() throws Exception
    {
        final AtomicInteger destroyed=new AtomicInteger();
        final AtomicInteger started=new AtomicInteger();
        final AtomicInteger stopped=new AtomicInteger();

        AggregateLifeCycle a0=new AggregateLifeCycle();
        
        AggregateLifeCycle a1=new AggregateLifeCycle()
        {
            @Override
            protected void doStart() throws Exception
            {
                started.incrementAndGet();
                super.doStart();
            }

            @Override
            protected void doStop() throws Exception
            {
                stopped.incrementAndGet();
                super.doStop();
            }
            
            @Override
            public void destroy()
            {
                destroyed.incrementAndGet();
                super.destroy();
            }

        };
        
        // Start the a1 bean before adding, makes it auto disjoint
        a1.start();
        
        // Now add it
        a0.addBean(a1);
        Assert.assertFalse(a0.isManaged(a1));
        
        a0.start();
        Assert.assertEquals(1,started.get());
        Assert.assertEquals(0,stopped.get());
        Assert.assertEquals(0,destroyed.get());
        
        a0.start();
        Assert.assertEquals(1,started.get());
        Assert.assertEquals(0,stopped.get());
        Assert.assertEquals(0,destroyed.get());
        
        a0.stop();
        Assert.assertEquals(1,started.get());
        Assert.assertEquals(0,stopped.get());
        Assert.assertEquals(0,destroyed.get());
        
        a1.stop();
        Assert.assertEquals(1,started.get());
        Assert.assertEquals(1,stopped.get());
        Assert.assertEquals(0,destroyed.get());
        
        a0.start();
        Assert.assertEquals(1,started.get());
        Assert.assertEquals(1,stopped.get());
        Assert.assertEquals(0,destroyed.get());
        
        a0.manage(a1);
        Assert.assertTrue(a0.isManaged(a1));

        a0.stop();
        Assert.assertEquals(1,started.get());
        Assert.assertEquals(1,stopped.get());
        Assert.assertEquals(0,destroyed.get());
        

        a0.start();
        Assert.assertEquals(2,started.get());
        Assert.assertEquals(1,stopped.get());
        Assert.assertEquals(0,destroyed.get());

        a0.stop();
        Assert.assertEquals(2,started.get());
        Assert.assertEquals(2,stopped.get());
        Assert.assertEquals(0,destroyed.get());
        

        a0.unmanage(a1);
        Assert.assertFalse(a0.isManaged(a1));
        
        a0.destroy();
        Assert.assertEquals(2,started.get());
        Assert.assertEquals(2,stopped.get());
        Assert.assertEquals(0,destroyed.get());
        
        a1.destroy();
        Assert.assertEquals(2,started.get());
        Assert.assertEquals(2,stopped.get());
        Assert.assertEquals(1,destroyed.get());
        
    }
    
    @Test
    public void testDumpable()
    {
        AggregateLifeCycle a0 = new AggregateLifeCycle();
        a0.dumpStdErr();
        
        System.err.println("--");
        AggregateLifeCycle aa0 = new AggregateLifeCycle();
        a0.addBean(aa0);
        a0.dumpStdErr();
        
        System.err.println("--");
        AggregateLifeCycle aa1 = new AggregateLifeCycle();
        a0.addBean(aa1);
        a0.dumpStdErr();
        
        System.err.println("--");
        AggregateLifeCycle aaa0 = new AggregateLifeCycle();
        aa0.addBean(aaa0);
        a0.dumpStdErr();   
        
        System.err.println("--");
        AggregateLifeCycle aa10 = new AggregateLifeCycle();
        aa1.addBean(aa10);
        a0.dumpStdErr();   
        

        System.err.println("--");
        final AggregateLifeCycle a1 = new AggregateLifeCycle();
        final AggregateLifeCycle a2 = new AggregateLifeCycle();
        final AggregateLifeCycle a3 = new AggregateLifeCycle();
        final AggregateLifeCycle a4 = new AggregateLifeCycle();
        

        AggregateLifeCycle aa = new AggregateLifeCycle()
        {
            @Override
            public void dump(Appendable out, String indent) throws IOException
            {
                out.append(this.toString()).append("\n");
                dump(out,indent,TypeUtil.asList(new Object[]{a1,a2}),TypeUtil.asList(new Object[]{a3,a4}));
            }
        };
        a0.addBean(aa);
        a0.dumpStdErr();   

        System.err.println("--");
        a2.addBean(aa0);
        a0.dumpStdErr(); 

        System.err.println("--");
        a0.unmanage(aa);
        a2.unmanage(aa0);
        a0.dumpStdErr(); 
        
    }
}
