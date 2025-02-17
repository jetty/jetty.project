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

package org.eclipse.jetty.http2.tests;

import java.nio.ByteBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http2.ErrorCode;
import org.eclipse.jetty.http2.api.Session;
import org.eclipse.jetty.http2.api.Stream;
import org.eclipse.jetty.http2.frames.HeadersFrame;
import org.eclipse.jetty.http2.frames.ResetFrame;
import org.eclipse.jetty.io.RetainableByteBuffer;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Blocker;
import org.eclipse.jetty.util.Callback;
import org.junit.jupiter.api.Test;

import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class CancelWriteTest extends AbstractTest
{
    @Test
    public void testCancelAfterWrite() throws Exception
    {
        CountDownLatch serverWriteSuccessLatch = new CountDownLatch(1);
        CountDownLatch serverWriteFailureLatch = new CountDownLatch(1);
        AtomicReference<Stream> clientStreamRef = new AtomicReference<>();

        start(new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                RetainableByteBuffer.Mutable buffer = server.getByteBufferPool().acquire(128 * 1024 * 1024, true);
                ByteBuffer byteBuffer = buffer.getByteBuffer();
                byteBuffer.clear();
                response.write(true, byteBuffer, Callback.from(() ->
                {
                    serverWriteSuccessLatch.countDown();

                    // Release the buffer.
                    buffer.release();

                    // Complete the Handler callback.
                    callback.succeeded();
                }, x ->
                {
                    serverWriteFailureLatch.countDown();

                    // Release the buffer.
                    buffer.release();

                    // Complete the Handler callback.
                    callback.failed(x);
                }));

                // Make the client reset the current stream.
                Stream clientStream = await().atMost(5, TimeUnit.SECONDS).until(clientStreamRef::get, notNullValue());
                clientStream.reset(new ResetFrame(clientStream.getId(), ErrorCode.CANCEL_STREAM_ERROR.code), Callback.NOOP);

                return true;
            }
        });

        Session session = newClientSession(new Session.Listener() {});
        try (Blocker.Promise<Stream> promise = Blocker.promise())
        {
            session.newStream(new HeadersFrame(newRequest("GET", HttpFields.EMPTY), null, false), promise, new Stream.Listener() {});
            clientStreamRef.set(promise.block());
        }

        Stream stream = clientStreamRef.get();
        await().atMost(5, TimeUnit.SECONDS).until(() -> stream.isReset() && stream.isClosed());

        assertTrue(serverWriteFailureLatch.await(5, TimeUnit.SECONDS));
        assertFalse(serverWriteSuccessLatch.await(1, TimeUnit.SECONDS));
    }
}
