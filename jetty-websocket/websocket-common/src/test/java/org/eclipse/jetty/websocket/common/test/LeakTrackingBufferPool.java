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

package org.eclipse.jetty.websocket.common.test;

import static org.hamcrest.Matchers.*;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.LeakTrackingByteBufferPool;
import org.eclipse.jetty.util.LeakDetector;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.junit.Assert;
import org.junit.rules.TestRule;
import org.junit.runner.Description;
import org.junit.runners.model.Statement;

public class LeakTrackingBufferPool extends LeakTrackingByteBufferPool implements TestRule
{
    private static final Logger LOG = Log.getLogger(LeakTrackingBufferPool.class);
    private final String id;
    private AtomicInteger leakCount = new AtomicInteger(0);

    public LeakTrackingBufferPool(String id, ByteBufferPool delegate)
    {
        super(delegate);
        this.id = id;
    }

    @Override
    protected void leaked(LeakDetector<ByteBuffer>.LeakInfo leakInfo)
    {
        String msg = String.format("%s ByteBuffer %s leaked at:",id,leakInfo.getResourceDescription());
        LOG.warn(msg,leakInfo.getStackFrames());
        leakCount.incrementAndGet();
    }

    public void assertNoLeaks()
    {
        Assert.assertThat("Leak Count for [" + id + "]",leakCount.get(),is(0));
    }

    public void clearTracking()
    {
        leakCount.set(0);
    }

    @Override
    public Statement apply(final Statement statement, Description description)
    {
        return new Statement()
        {
            @Override
            public void evaluate() throws Throwable
            {
                clearTracking();
                statement.evaluate();
                assertNoLeaks();
            }
        };
    }
}
