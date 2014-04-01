//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.util;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.hamcrest.Matchers;
import org.junit.Assert;
import org.junit.Test;

public class BlockingCallbackTest
{
    public BlockingCallbackTest()
    {
    }
    
    @Test
    public void testDone() throws Exception
    {
        final BlockingCallback fcb= new BlockingCallback();
        fcb.succeeded();
        long start=System.currentTimeMillis();
        fcb.block();
        Assert.assertThat(System.currentTimeMillis()-start,Matchers.lessThan(500L));     
    }
    
    @Test
    public void testGetDone() throws Exception
    {
        final BlockingCallback fcb= new BlockingCallback();
        final CountDownLatch latch = new CountDownLatch(1);
        
        new Thread(new Runnable()
        {
            @Override
            public void run()
            {
                latch.countDown();
                try{TimeUnit.MILLISECONDS.sleep(100);}catch(Exception e){e.printStackTrace();}
                fcb.succeeded();
            }
        }).start();
        
        latch.await();
        long start=System.currentTimeMillis();
        fcb.block();
        Assert.assertThat(System.currentTimeMillis()-start,Matchers.greaterThan(10L)); 
        Assert.assertThat(System.currentTimeMillis()-start,Matchers.lessThan(1000L)); 
    }
    
    @Test
    public void testFailed() throws Exception
    {
        final BlockingCallback fcb= new BlockingCallback();
        Exception ex=new Exception("FAILED");
        fcb.failed(ex);
        
        long start=System.currentTimeMillis();
        try
        {
            fcb.block();
            Assert.fail();
        }
        catch(IOException ee)
        {
            Assert.assertEquals(ex,ee.getCause());
        }
        Assert.assertThat(System.currentTimeMillis()-start,Matchers.lessThan(100L));     
    }
    
    @Test
    public void testGetFailed() throws Exception
    {
        final BlockingCallback fcb= new BlockingCallback();
        final Exception ex=new Exception("FAILED");
        final CountDownLatch latch = new CountDownLatch(1);
        
        new Thread(new Runnable()
        {
            @Override
            public void run()
            {
                latch.countDown();
                try{TimeUnit.MILLISECONDS.sleep(100);}catch(Exception e){e.printStackTrace();}
                fcb.failed(ex);
            }
        }).start();
        
        latch.await();
        long start=System.currentTimeMillis();
        try
        {
            fcb.block();
            Assert.fail();
        }
        catch(IOException ee)
        {
            Assert.assertEquals(ex,ee.getCause());
        }
        Assert.assertThat(System.currentTimeMillis()-start,Matchers.greaterThan(10L)); 
        Assert.assertThat(System.currentTimeMillis()-start,Matchers.lessThan(1000L));
    }
        
    
}
