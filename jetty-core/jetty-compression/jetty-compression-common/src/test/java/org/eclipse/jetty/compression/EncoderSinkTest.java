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

package org.eclipse.jetty.compression;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.util.Blocker;
import org.eclipse.jetty.util.Callback;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class EncoderSinkTest
{
    @Test
    public void testDelegateSinkWriteFailureReleaseCount()
    {
        Content.Sink sink = (last, callback, buffers) -> callback.failed(new ArithmeticException());
        var encoderSink = new EncoderSink(sink)
        {
            final AtomicInteger releaseCounter = new AtomicInteger();

            @Override
            protected WriteRecord encode(boolean last, ByteBuffer content)
            {
                return new WriteRecord(last, content, Callback.NOOP);
            }

            @Override
            protected void release()
            {
                releaseCounter.incrementAndGet();
            }
        };

        try (Blocker.Callback cb = Blocker.callback())
        {
            encoderSink.write(true, ByteBuffer.allocate(128), cb);
            assertThrows(ArithmeticException.class, cb::block);
        }

        assertThat(encoderSink.releaseCounter.get(), is(1));
    }
}
