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

import static org.hamcrest.Matchers.is;

import org.eclipse.jetty.util.ProcessorUtils;
import org.junit.Test;

public class ExecutorThreadPoolTest extends AbstractThreadPoolTest
{
    @Test
    public void testThreadBudget_HighCoreCount_MinEqualsMax() throws Exception
    {
        ProcessorUtils.setAvailableProcessors(64);
        ExecutorThreadPool pool = new ExecutorThreadPool(1408, 1408);
        assertBudgetCheck(pool, "Pool of max==min should be valid", is(true));
    }

    @Test
    public void testThreadBudget_HighCoreCount() throws Exception
    {
        ProcessorUtils.setAvailableProcessors(64);
        ExecutorThreadPool pool = new ExecutorThreadPool(64, 1);
        assertBudgetCheck(pool, "Pool at core count is invalid", is(false));
    }

    @Test
    public void testThreadBudget_HighCoreCount_LowThreadMax() throws Exception
    {
        ProcessorUtils.setAvailableProcessors(64);
        ExecutorThreadPool pool = new ExecutorThreadPool(8, 1);
        assertBudgetCheck(pool, "Pool smaller then core count should be valid", is(true));
    }
}
