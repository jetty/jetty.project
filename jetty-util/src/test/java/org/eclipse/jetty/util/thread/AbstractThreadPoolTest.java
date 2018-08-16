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
import org.hamcrest.Matcher;
import org.junit.AfterClass;
import org.junit.BeforeClass;

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

    public void assertBudgetCheck(ThreadPool.SizedThreadPool pool, String message, Matcher<Boolean> matcher) throws Exception
    {
        try
        {
            if (pool instanceof LifeCycle)
            {
                ((LifeCycle) pool).start();
            }
            assertThat("ThreadPoolBudget.check(): " + message, pool.getThreadPoolBudget().check(), matcher);
        }
        finally
        {
            if (pool instanceof LifeCycle)
            {
                try
                {
                    ((LifeCycle) pool).stop();
                }
                catch (Exception ignore)
                {
                }
            }
        }
    }
}
