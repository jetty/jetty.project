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
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.util.Blocker;
import org.eclipse.jetty.util.Callback;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.sameInstance;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class EncoderSinkTest
{
    @Test
    public void testDelegateSinkWriteFailureReleaseCount()
    {
        Content.Sink sink = (last, byteBuffer, callback) -> callback.failed(new ArithmeticException());
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

    @Test
    public void testAbortBeforeAnyWrite()
    {
        CountingEncoderSink encoderSink = new CountingEncoderSink((last, byteBuffer, callback) -> callback.succeeded());

        encoderSink.abort(new ArithmeticException());
        assertThat(encoderSink.releaseCounter.get(), is(1));

        assertWriteFails(encoderSink);
        assertThat(encoderSink.encodeCounter.get(), is(0));
        assertThat(encoderSink.releaseCounter.get(), is(1));
    }

    @Test
    public void testAbortBetweenWrites() throws Exception
    {
        CountingEncoderSink encoderSink = new CountingEncoderSink((last, byteBuffer, callback) -> callback.succeeded());

        try (Blocker.Callback cb = Blocker.callback())
        {
            encoderSink.write(false, ByteBuffer.allocate(128), cb);
            cb.block();
        }
        assertThat(encoderSink.releaseCounter.get(), is(0));

        encoderSink.abort(new ArithmeticException());
        assertThat(encoderSink.releaseCounter.get(), is(1));

        assertWriteFails(encoderSink);
        assertThat(encoderSink.encodeCounter.get(), is(1));
        assertThat(encoderSink.releaseCounter.get(), is(1));
    }

    @Test
    public void testAbortDuringPendingWriteThatSucceeds()
    {
        testAbortDuringPendingWrite(true);
    }

    @Test
    public void testAbortDuringPendingWriteThatFails()
    {
        testAbortDuringPendingWrite(false);
    }

    private void testAbortDuringPendingWrite(boolean succeedPendingWrite)
    {
        AtomicReference<Callback> pendingWrite = new AtomicReference<>();
        CountingEncoderSink encoderSink = new CountingEncoderSink((last, byteBuffer, callback) -> pendingWrite.set(callback));

        AtomicReference<Throwable> writeResult = new AtomicReference<>();
        encoderSink.write(false, ByteBuffer.allocate(128), Callback.from(() -> writeResult.set(new Throwable("succeeded")), writeResult::set));
        assertThat(pendingWrite.get(), notNullValue());

        ArithmeticException failure = new ArithmeticException();
        encoderSink.abort(failure);
        // The write is in progress, the release must wait for it to complete.
        assertThat(encoderSink.releaseCounter.get(), is(0));
        assertThat(writeResult.get(), nullValue());

        if (succeedPendingWrite)
            pendingWrite.get().succeeded();
        else
            pendingWrite.get().failed(new NegativeArraySizeException());

        assertThat(encoderSink.releaseCounter.get(), is(1));
        // The write callback is failed with the abort cause, even if the pending write succeeded.
        assertThat(writeResult.get(), sameInstance(failure));
        if (!succeedPendingWrite)
            assertThat(failure.getSuppressed()[0], instanceOf(NegativeArraySizeException.class));

        assertWriteFails(encoderSink);
        assertThat(encoderSink.encodeCounter.get(), is(1));
        assertThat(encoderSink.releaseCounter.get(), is(1));
    }

    @Test
    public void testAbortAfterLastWrite() throws Exception
    {
        CountingEncoderSink encoderSink = new CountingEncoderSink((last, byteBuffer, callback) -> callback.succeeded());

        try (Blocker.Callback cb = Blocker.callback())
        {
            encoderSink.write(true, ByteBuffer.allocate(128), cb);
            cb.block();
        }
        assertThat(encoderSink.releaseCounter.get(), is(1));

        encoderSink.abort(new ArithmeticException());
        encoderSink.abort(new ArithmeticException());
        assertThat(encoderSink.releaseCounter.get(), is(1));
    }

    @Test
    public void testAbortTwice()
    {
        CountingEncoderSink encoderSink = new CountingEncoderSink((last, byteBuffer, callback) -> callback.succeeded());

        encoderSink.abort(new ArithmeticException());
        encoderSink.abort(new ArithmeticException());
        assertThat(encoderSink.releaseCounter.get(), is(1));
    }

    private static void assertWriteFails(EncoderSink encoderSink)
    {
        try (Blocker.Callback cb = Blocker.callback())
        {
            encoderSink.write(true, ByteBuffer.allocate(128), cb);
            assertThrows(Throwable.class, cb::block);
        }
    }

    private static class CountingEncoderSink extends EncoderSink
    {
        private final AtomicInteger encodeCounter = new AtomicInteger();
        private final AtomicInteger releaseCounter = new AtomicInteger();

        private CountingEncoderSink(Content.Sink sink)
        {
            super(sink);
        }

        @Override
        protected WriteRecord encode(boolean last, ByteBuffer content)
        {
            if (!content.hasRemaining() && !last)
                return null;
            encodeCounter.incrementAndGet();
            ByteBuffer output = content.slice();
            content.position(content.limit());
            return new WriteRecord(last, output, Callback.NOOP);
        }

        @Override
        protected void release()
        {
            releaseCounter.incrementAndGet();
        }
    }
}
