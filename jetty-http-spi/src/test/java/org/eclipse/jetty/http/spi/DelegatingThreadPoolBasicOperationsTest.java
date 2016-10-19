//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.http.spi;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.verify;
import static org.powermock.api.mockito.PowerMockito.mock;
import static org.powermock.api.mockito.PowerMockito.when;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import org.eclipse.jetty.http.spi.util.Pool;
import org.eclipse.jetty.http.spi.util.PrintTask;
import org.eclipse.jetty.http.spi.util.SpiConstants;
import org.eclipse.jetty.http.spi.util.SpiUtility;
import org.eclipse.jetty.util.thread.ThreadPool;
import org.junit.Test;

public class DelegatingThreadPoolBasicOperationsTest extends DelegateThreadPoolBase
{

    private ThreadPoolExecutor newThreadPoolExecutor;

    private ThreadPoolExecutor previousThreadPoolExecutor;

    private ThreadPool threadPool;

    @Test
    public void testExecutorInstances() throws Exception
    {
        // given
        newThreadPoolExecutor = SpiUtility.getThreadPoolExecutor(Pool.CORE_POOL_SIZE.getValue(),SpiConstants.poolInfo);
        previousThreadPoolExecutor = (ThreadPoolExecutor)delegatingThreadPool.getExecutor();

        // when
        delegatingThreadPool.setExecutor(newThreadPoolExecutor);

        // then
        assertNotEquals("Executor instances shouldn't be equal, as we have modified the executor",(ThreadPoolExecutor)delegatingThreadPool.getExecutor(),
                previousThreadPoolExecutor);
    }

    private void setUpForThreadPools() throws Exception
    {
        ThreadPool threadPool = mock(ThreadPool.class);
        when(threadPool.getIdleThreads()).thenReturn(SpiConstants.ZERO);
        when(threadPool.getThreads()).thenReturn(SpiConstants.ZERO);
        when(threadPool.isLowOnThreads()).thenReturn(false);
        delegatingThreadPool = new DelegatingThreadPool(threadPool);
    }

    @Test
    public void testBasicOperationsForThreadPool() throws Exception
    {
        // given
        setUpForThreadPools();

        // then
        assertEquals("Idle thread count must be zero as no job ran so far",SpiConstants.ZERO,delegatingThreadPool.getIdleThreads());
        assertEquals("Pool count must be zero as no job ran so far",SpiConstants.ZERO,delegatingThreadPool.getThreads());
        assertFalse("Threads must haeve been available as no job has been started so far",delegatingThreadPool.isLowOnThreads());
    }

    @Test
    public void testBasicOperationsForThreadPoolExecutors() throws Exception
    {
        // given
        delegatingThreadPool = SpiUtility.getDelegatingThreadPool();

        // then
        assertEquals("Idle thread count must be zero as no job ran so far",Pool.DEFAULT_SIZE.getValue(),delegatingThreadPool.getIdleThreads());
        assertEquals("Pool count must be zero as no job ran so far",Pool.DEFAULT_SIZE.getValue(),delegatingThreadPool.getThreads());
        assertFalse("Threads must haeve been available as no job has been started so far",delegatingThreadPool.isLowOnThreads());
    }

    @Test
    public void testBasicOperationsForExecutors() throws Exception
    {
        // given
        Executor executor = mock(Executor.class);

        // when
        delegatingThreadPool = new DelegatingThreadPool(executor);

        // then
        assertEquals("Idle thread count must be zero as no job ran so far",SpiConstants.MINUS_ONE,delegatingThreadPool.getIdleThreads());
        assertEquals("Pool count must be zero as no job ran so far",SpiConstants.MINUS_ONE,delegatingThreadPool.getThreads());
        assertFalse("Threads must haeve been available as no job has been started so far",delegatingThreadPool.isLowOnThreads());
    }

    @Test
    public void testTask() throws Exception
    {
        // given
        PrintTask printTask = null;

        // when
        for (int i = 0; i < 30; i++)
        {
            printTask = new PrintTask();
            delegatingThreadPool.execute(printTask);
        }

        // then
        // Based on processor speed/job execution time thread count will vary.
        // So checking the boundary conditions(DEFAULT_SIZE,MAXIMUM_POOL_SIZE).
        String succssMsgOnMaxSize = "Current thread pool must be always  less than or equal " + "to  max pool size, even though the jobs are more";
        String succssMsgOnMinSize = "Current thread pool must be always  greater than or equal to defalut size";
        assertTrue(succssMsgOnMaxSize,delegatingThreadPool.getThreads() <= Pool.MAXIMUM_POOL_SIZE.getValue());
        assertTrue(succssMsgOnMinSize,delegatingThreadPool.getThreads() >= Pool.DEFAULT_SIZE.getValue());
    }

    @Test
    public void testJoinForThreadPools() throws Exception
    {
        // given
        threadPool = mock(ThreadPool.class);
        delegatingThreadPool = new DelegatingThreadPool(threadPool);

        // when
        delegatingThreadPool.join();

        // then
        verify(threadPool).join();
    }
}
