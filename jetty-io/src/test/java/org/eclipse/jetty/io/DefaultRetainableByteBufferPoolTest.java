//
// ========================================================================
// Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.io;

import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.core.Is.is;

public class DefaultRetainableByteBufferPoolTest
{
    @Test
    public void testAcquireRelease()
    {
        DefaultRetainableByteBufferPool pool = new DefaultRetainableByteBufferPool();

        for (int i = 0; i < 10; i++)
        {
            {
                RetainableByteBuffer buffer = pool.acquire(10, true);
                assertThat(buffer, is(notNullValue()));
                RetainableByteBuffer buffer2 = pool.acquire(10, true);
                assertThat(buffer2, is(notNullValue()));
                buffer.release();
                buffer2.release();
            }
            {
                RetainableByteBuffer buffer = pool.acquire(16385, true);
                assertThat(buffer, is(notNullValue()));
                buffer.release();
            }
            {
                RetainableByteBuffer buffer = pool.acquire(32768, true);
                assertThat(buffer, is(notNullValue()));
                buffer.release();
            }
            {
                RetainableByteBuffer buffer = pool.acquire(32768, false);
                assertThat(buffer, is(notNullValue()));
                buffer.release();
            }
        }

        assertThat(pool.getDirectByteBufferCount(), is(4L));
        assertThat(pool.getHeapByteBufferCount(), is(1L));
    }
}
