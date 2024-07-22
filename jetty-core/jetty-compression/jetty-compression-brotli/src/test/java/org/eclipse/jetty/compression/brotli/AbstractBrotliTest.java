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

package org.eclipse.jetty.compression.brotli;

import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.jetty.io.ArrayByteBufferPool;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.RetainableByteBuffer;
import org.eclipse.jetty.util.component.LifeCycle;
import org.junit.jupiter.api.AfterEach;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

public abstract class AbstractBrotliTest
{
    private final AtomicInteger poolCounter = new AtomicInteger();

    @AfterEach
    public void after()
    {
        assertThat(poolCounter.get(), is(0));
    }

    protected BrotliCompression brotli;

    protected void startBrotli() throws Exception
    {
        startBrotli(-1);
    }

    protected void startBrotli(int bufferSize) throws Exception
    {
        brotli = new BrotliCompression();
        if (bufferSize > 0)
            brotli.setBufferSize(bufferSize);

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
        brotli.setByteBufferPool(pool);
        brotli.start();
    }

    @AfterEach
    public void stopBrotli()
    {
        LifeCycle.stop(brotli);
        assertThat("ByteBufferPool counter", poolCounter.get(), is(0));
    }
}
