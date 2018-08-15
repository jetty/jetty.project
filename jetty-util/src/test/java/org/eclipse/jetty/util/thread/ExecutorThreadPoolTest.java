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

import static org.hamcrest.Matchers.instanceOf;

import org.eclipse.jetty.util.ProcessorUtils;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.util.log.StdErrCapture;
import org.eclipse.jetty.util.log.StdErrLog;
import org.junit.AfterClass;
import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;

public class ExecutorThreadPoolTest
{
    private static int originalCoreCount;
    private static StdErrLog originalBudgetLogger;

    @BeforeClass
    public static void saveState()
    {
        originalCoreCount = ProcessorUtils.availableProcessors();
        Assume.assumeThat(ThreadPoolBudget.LOG, instanceOf(StdErrLog.class));
        originalBudgetLogger = (StdErrLog) ThreadPoolBudget.LOG;
    }

    @AfterClass
    public static void restoreState()
    {
        ProcessorUtils.setAvailableProcessors(originalCoreCount);
        originalBudgetLogger.setStdErrStream(System.err);
    }

    private void stop(LifeCycle lifeCycle)
    {
        try
        {
            lifeCycle.stop();
        }
        catch (Exception ignore)
        {
        }
    }

    @Test
    public void testThreadBudget_HighCoreCount_MinEqualsMax() throws Exception
    {
        ProcessorUtils.setAvailableProcessors(64);
        StdErrCapture output = new StdErrCapture(originalBudgetLogger);
        ExecutorThreadPool pool = new ExecutorThreadPool(1408, 1408);
        try
        {
            pool.start();
            pool.getThreadPoolBudget().check();
            output.assertNotContains("WARN:");
        }
        finally
        {
            stop(pool);
        }
    }

    @Test
    public void testThreadBudget_HighCoreCount() throws Exception
    {
        ProcessorUtils.setAvailableProcessors(64);
        StdErrCapture output = new StdErrCapture(originalBudgetLogger);
        ExecutorThreadPool pool = new ExecutorThreadPool(64, 1);
        try
        {
            pool.start();
            pool.getThreadPoolBudget().check();
            output.assertContains("WARN:");
        }
        finally
        {
            stop(pool);
        }
    }

    @Test
    public void testThreadBudget_HighCoreCount_LowThreadMax() throws Exception
    {
        ProcessorUtils.setAvailableProcessors(64);
        StdErrCapture output = new StdErrCapture(originalBudgetLogger);
        ExecutorThreadPool pool = new ExecutorThreadPool(8, 1);
        try
        {
            pool.start();
            pool.getThreadPoolBudget().check();
            output.assertNotContains("WARN:");
        }
        finally
        {
            stop(pool);
        }
    }
}
