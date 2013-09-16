//
//  ========================================================================
//  Copyright (c) 1995-2013 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.policy;

import java.io.File;
import java.util.HashMap;
import java.util.concurrent.atomic.AtomicInteger;

import junit.framework.Assert;

import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.toolchain.test.OS;
import org.junit.Before;
import org.junit.Test;

public class PolicyMonitorTest
{    
    
    private HashMap<String, String> evaluator = new HashMap<String, String>();

    @Before
    public void init() throws Exception
    {
        System.setProperty( "basedir", MavenTestingUtils.getBaseURI().toASCIIString() );
    }
    
    @Test
    public void testSimpleLoading() throws Exception
    {
        final AtomicInteger count = new AtomicInteger(0);
        
        PolicyMonitor monitor = new PolicyMonitor(new File(MavenTestingUtils.getTargetDir(),
                "test-classes/monitor-test-1").getAbsolutePath())
        {
            
            @Override
            public void onPolicyChange(PolicyBlock grant)
            {
                count.incrementAndGet();
            }
        };
        monitor.setScanInterval(1);
        
        monitor.start();
        
        while (!monitor.isInitialized() )
        {
            Thread.sleep(100);
        }

        Assert.assertEquals(1,count.get());
        monitor.stop();
    }
    
    @Test
    public void testSimpleReloading() throws Exception
    {
        if (OS.IS_WINDOWS)
        {
            return;
        }
        
        final AtomicInteger count = new AtomicInteger(0);
        
        PolicyMonitor monitor = new PolicyMonitor(new File(MavenTestingUtils.getTargetDir(),
                "test-classes/monitor-test-2").getAbsolutePath())
        {       
            @Override
            public void onPolicyChange(PolicyBlock grant)
            {
                count.incrementAndGet();
            }
        };

        monitor.setScanInterval(1);
        
        monitor.start();
        monitor.waitForScan();
        monitor.waitForScan();

        File permFile =new File(MavenTestingUtils.getTargetDir(),
                "test-classes/monitor-test-2/global-all-permission.policy");
        
	    // Wait so that time is definitely different
        monitor.waitForScan();
        permFile.setLastModified(System.currentTimeMillis());
                        
        monitor.waitForScan();
        monitor.waitForScan();

        Assert.assertEquals(2,count.get());
        monitor.stop();
    }
    
    @Test
    public void testLoading() throws Exception
    {
        final AtomicInteger count = new AtomicInteger(0);
        
        PolicyMonitor monitor = new PolicyMonitor(new File(MavenTestingUtils.getTargetDir(),
            "test-classes/monitor-test-3").getAbsolutePath())
        {       
            @Override
            public void onPolicyChange(PolicyBlock grant)
            {
                count.incrementAndGet();
            }
        };
        
        monitor.setScanInterval(1);
        
        monitor.start();
        
        while (! monitor.isInitialized() )
        {
            Thread.sleep(100);
        }
        
        Assert.assertEquals(16,count.get());
        monitor.stop();
    }
}
