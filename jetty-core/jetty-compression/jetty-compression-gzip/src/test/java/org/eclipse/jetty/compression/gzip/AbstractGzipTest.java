//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.compression.gzip;

import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.jetty.io.ArrayByteBufferPool;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.RetainableByteBuffer;
import org.eclipse.jetty.util.component.LifeCycle;
import org.junit.jupiter.api.AfterEach;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

public abstract class AbstractGzipTest
{
    private final AtomicInteger poolCounter = new AtomicInteger();

    @AfterEach
    public void after()
    {
        assertThat(poolCounter.get(), is(0));
    }

    protected GzipCompression gzip;

    protected void startGzip() throws Exception
    {
        startGzip(-1);
    }

    protected void startGzip(int bufferSize) throws Exception
    {
        gzip = new GzipCompression();
        if (bufferSize > 0)
            gzip.setBufferSize(bufferSize);

        ByteBufferPool pool = new ByteBufferPool.Wrapper(new ArrayByteBufferPool())
        {
            @Override
            public RetainableByteBuffer.Mutable acquire(int size, boolean direct)
            {
                poolCounter.incrementAndGet();
                return new RetainableByteBuffer.Mutable.Wrapper(super.acquire(size, direct))
                {
                    @Override
                    public boolean release()
                    {
                        boolean released = super.release();
                        if (released)
                            poolCounter.decrementAndGet();
                        return released;
                    }
                };
            }
        };
        gzip.setByteBufferPool(pool);
        gzip.start();
    }

    @AfterEach
    public void stopGzip()
    {
        LifeCycle.stop(gzip);
        assertThat(poolCounter.get(), is(0));
    }
}
