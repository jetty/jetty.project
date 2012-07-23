package org.eclipse.jetty.util.component;
//========================================================================
//Copyright (c) 2006-2012 Mort Bay Consulting Pty. Ltd.
//------------------------------------------------------------------------
//All rights reserved. This program and the accompanying materials
//are made available under the terms of the Eclipse Public License v1.0
//and Apache License v2.0 which accompanies this distribution.
//The Eclipse Public License is available at 
//http://www.eclipse.org/legal/epl-v10.html
//The Apache License v2.0 is available at
//http://www.opensource.org/licenses/apache2.0.php
//You may elect to redistribute this code under either of these licenses. 
//========================================================================

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
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
    public void testDumpable() throws Exception
    {
        AggregateLifeCycle a0 = new AggregateLifeCycle();
        String dump=trim(a0.dump());
        dump=check(dump,"org.eclipse.jetty.util.component.AggregateLifeCycl");
        
        AggregateLifeCycle aa0 = new AggregateLifeCycle();
        a0.addBean(aa0);
        dump=trim(a0.dump());
        dump=check(dump,"org.eclipse.jetty.util.component.AggregateLifeCycl");
        dump=check(dump," +- org.eclipse.jetty.util.component.AggregateLife");
        
        AggregateLifeCycle aa1 = new AggregateLifeCycle();
        a0.addBean(aa1);
        dump=trim(a0.dump());
        dump=check(dump,"org.eclipse.jetty.util.component.AggregateLifeCycl");
        dump=check(dump," +- org.eclipse.jetty.util.component.AggregateLife");
        dump=check(dump," +- org.eclipse.jetty.util.component.AggregateLife");
        dump=check(dump,"");
        
        AggregateLifeCycle aaa0 = new AggregateLifeCycle();
        aa0.addBean(aaa0);
        dump=trim(a0.dump());
        dump=check(dump,"org.eclipse.jetty.util.component.AggregateLifeCycl");
        dump=check(dump," +- org.eclipse.jetty.util.component.AggregateLife");
        dump=check(dump," |   +- org.eclipse.jetty.util.component.Aggregate");
        dump=check(dump," +- org.eclipse.jetty.util.component.AggregateLife");
        dump=check(dump,"");
        
        AggregateLifeCycle aa10 = new AggregateLifeCycle();
        aa1.addBean(aa10);
        dump=trim(a0.dump());
        dump=check(dump,"org.eclipse.jetty.util.component.AggregateLifeCycl");
        dump=check(dump," +- org.eclipse.jetty.util.component.AggregateLife");
        dump=check(dump," |   +- org.eclipse.jetty.util.component.Aggregate");
        dump=check(dump," +- org.eclipse.jetty.util.component.AggregateLife");
        dump=check(dump,"     +- org.eclipse.jetty.util.component.Aggregate");
        dump=check(dump,"");
        
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
        dump=trim(a0.dump());
        dump=check(dump,"org.eclipse.jetty.util.component.AggregateLifeCycl");
        dump=check(dump," +- org.eclipse.jetty.util.component.AggregateLife");
        dump=check(dump," |   +- org.eclipse.jetty.util.component.Aggregate");
        dump=check(dump," +- org.eclipse.jetty.util.component.AggregateLife");
        dump=check(dump," |   +- org.eclipse.jetty.util.component.Aggregate");
        dump=check(dump," +- org.eclipse.jetty.util.component.AggregateLife");
        dump=check(dump,"     +- org.eclipse.jetty.util.component.Aggregate");
        dump=check(dump,"     +- org.eclipse.jetty.util.component.Aggregate");
        dump=check(dump,"     |");
        dump=check(dump,"     +- org.eclipse.jetty.util.component.Aggregate");
        dump=check(dump,"     +- org.eclipse.jetty.util.component.Aggregate");
        dump=check(dump,"");

        a2.addBean(aa0);
        dump=trim(a0.dump());
        dump=check(dump,"org.eclipse.jetty.util.component.AggregateLifeCycl");
        dump=check(dump," +- org.eclipse.jetty.util.component.AggregateLife");
        dump=check(dump," |   +- org.eclipse.jetty.util.component.Aggregate");
        dump=check(dump," +- org.eclipse.jetty.util.component.AggregateLife");
        dump=check(dump," |   +- org.eclipse.jetty.util.component.Aggregate");
        dump=check(dump," +- org.eclipse.jetty.util.component.AggregateLife");
        dump=check(dump,"     +- org.eclipse.jetty.util.component.Aggregate");
        dump=check(dump,"     +- org.eclipse.jetty.util.component.Aggregate");
        dump=check(dump,"     |   +- org.eclipse.jetty.util.component.Aggre");
        dump=check(dump,"     |       +- org.eclipse.jetty.util.component.A");
        dump=check(dump,"     |");
        dump=check(dump,"     +- org.eclipse.jetty.util.component.Aggregate");
        dump=check(dump,"     +- org.eclipse.jetty.util.component.Aggregate");
        dump=check(dump,"");

        a2.unmanage(aa0);
        dump=trim(a0.dump());
        dump=check(dump,"org.eclipse.jetty.util.component.AggregateLifeCycl");
        dump=check(dump," +- org.eclipse.jetty.util.component.AggregateLife");
        dump=check(dump," |   +- org.eclipse.jetty.util.component.Aggregate");
        dump=check(dump," +- org.eclipse.jetty.util.component.AggregateLife");
        dump=check(dump," |   +- org.eclipse.jetty.util.component.Aggregate");
        dump=check(dump," +- org.eclipse.jetty.util.component.AggregateLife");
        dump=check(dump,"     +- org.eclipse.jetty.util.component.Aggregate");
        dump=check(dump,"     +- org.eclipse.jetty.util.component.Aggregate");
        dump=check(dump,"     |   +~ org.eclipse.jetty.util.component.Aggre");
        dump=check(dump,"     |");
        dump=check(dump,"     +- org.eclipse.jetty.util.component.Aggregate");
        dump=check(dump,"     +- org.eclipse.jetty.util.component.Aggregate");
        dump=check(dump,"");
        
        a0.unmanage(aa);
        dump=trim(a0.dump());
        dump=check(dump,"org.eclipse.jetty.util.component.AggregateLifeCycl");
        dump=check(dump," +- org.eclipse.jetty.util.component.AggregateLife");
        dump=check(dump," |   +- org.eclipse.jetty.util.component.Aggregate");
        dump=check(dump," +- org.eclipse.jetty.util.component.AggregateLife");
        dump=check(dump," |   +- org.eclipse.jetty.util.component.Aggregate");
        dump=check(dump," +~ org.eclipse.jetty.util.component.AggregateLife");
        dump=check(dump,"");
        
    }
    
    String trim(String s) throws IOException
    {
        StringBuilder b=new StringBuilder();
        BufferedReader reader=new BufferedReader(new StringReader(s));
        
        for (String line=reader.readLine();line!=null;line=reader.readLine())
        {
            if (line.length()>50)
                line=line.substring(0,50);
            b.append(line).append('\n');
        }
        
        return b.toString();
    }
    
    String check(String s,String x)
    {
        String r=s;
        int nl = s.indexOf('\n');
        if (nl>0)
        {
            r=s.substring(nl+1);
            s=s.substring(0,nl);
        }
        
        Assert.assertEquals(x,s);
        
        return r;
    }
    
    
    
}
