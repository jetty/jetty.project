//
//  ========================================================================
//  Copyright (c) 1995-2018 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.util.thread;

import static org.hamcrest.MatcherAssert.assertThat;

import org.eclipse.jetty.util.ProcessorUtils;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.util.thread.ThreadPool.SizedThreadPool;
import org.hamcrest.Matcher;
import org.hamcrest.Matchers;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

public abstract class AbstractThreadPoolTest
{
    private static int originalCoreCount;

    @BeforeClass
    public static void saveState()
    {
        originalCoreCount = ProcessorUtils.availableProcessors();
    }

    @AfterClass
    public static void restoreState()
    {
        ProcessorUtils.setAvailableProcessors(originalCoreCount);
    }

    abstract protected SizedThreadPool newPool(int max);
    
    @Test
    public void testBudget_constructMaxThenLease()
    {
        SizedThreadPool pool = newPool(4);
        
        pool.getThreadPoolBudget().leaseTo(this,2);

        try
        {
            pool.getThreadPoolBudget().leaseTo(this,3);
            Assert.fail();
        }
        catch(IllegalStateException e)
        {
            Assert.assertThat(e.getMessage(),Matchers.containsString("Insufficient configured threads"));
        }

        pool.getThreadPoolBudget().leaseTo(this,1);
    }

    @Test
    public void testBudget_LeaseThenSetMax()
    {
        SizedThreadPool pool = newPool(4);
        
        pool.getThreadPoolBudget().leaseTo(this,2);
        
        pool.setMaxThreads(3);

        try
        {
            pool.setMaxThreads(1);
            Assert.fail();
        }
        catch(IllegalStateException e)
        {
            Assert.assertThat(e.getMessage(),Matchers.containsString("Insufficient configured threads"));
        }

        Assert.assertThat(pool.getMaxThreads(),Matchers.is(3));
        
    }
    
}
