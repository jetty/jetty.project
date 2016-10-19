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

import static org.junit.Assert.fail;
import static org.powermock.api.mockito.PowerMockito.mock;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import org.eclipse.jetty.http.spi.util.Pool;
import org.eclipse.jetty.http.spi.util.SpiConstants;
import org.eclipse.jetty.http.spi.util.SpiUtility;
import org.junit.Test;

public class DelegatingThreadPoolExceptionTest extends DelegateThreadPoolBase
{

    private ThreadPoolExecutor threadPoolExecutor;

    @Test(expected = IllegalStateException.class)
    public void testJoinIllegalStateException() throws Exception
    {
        // given
        Executor mockExecutor = mock(Executor.class);
        delegatingThreadPool.setExecutor(mockExecutor);

        // when
        delegatingThreadPool.join();

        // then
        fail("A IllegalStateException must have occured by now as executor type " + "not in ThreadPool or ExecutorService.");
    }

    @Test(expected = IllegalStateException.class)
    public void testSetExecutorIllegalStateException() throws Exception
    {
        // given
        delegatingThreadPool.start();
        threadPoolExecutor = SpiUtility.getThreadPoolExecutor(Pool.CORE_POOL_SIZE.getValue(),SpiConstants.poolInfo);

        // when
        delegatingThreadPool.setExecutor(threadPoolExecutor);

        // then
        fail("A IllegalStateException must have occured by now as current threadpool "
                + "has been already started. So it shouldn't allow us to reset the pool.");
    }
}
