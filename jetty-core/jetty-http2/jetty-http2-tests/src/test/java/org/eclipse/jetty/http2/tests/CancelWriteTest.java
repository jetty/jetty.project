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
import java.util.concurrent.atomic.AtomicBoolean;

import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http2.ErrorCode;
import org.eclipse.jetty.http2.api.Session;
import org.eclipse.jetty.http2.api.Stream;
import org.eclipse.jetty.http2.frames.HeadersFrame;
import org.eclipse.jetty.http2.frames.ResetFrame;
import org.eclipse.jetty.io.AbstractEndPoint;
import org.eclipse.jetty.io.RetainableByteBuffer;
import org.eclipse.jetty.io.WriteFlusher;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.HttpStream;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.buffer.ReadableBuffer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class CancelWriteTest extends AbstractTest
{
    @Test
    @DisabledOnOs(value = OS.WINDOWS, disabledReason = "Fails on Windows")
    public void testCancelCongestedWrite() throws Exception
    {
        CountDownLatch serverFlusherPendingLatch = new CountDownLatch(1);
        CountDownLatch serverWriteSuccessLatch = new CountDownLatch(1);
        CountDownLatch serverWriteFailureLatch = new CountDownLatch(1);

        start(new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                AtomicBoolean httpStreamCancelled = new AtomicBoolean();
                request.addHttpStreamWrapper(w -> new HttpStream.Wrapper(w)
                {
                    @Override
                    public Runnable cancelSend(Throwable cause, Callback appCallback)
                    {
                        return super.cancelSend(cause, Callback.from(appCallback::succeeded, x ->
                        {
                            // Make sure this callback gets called before the Response.write() one.
                            httpStreamCancelled.set(true);
                            appCallback.failed(x);
                        }));
                    }
                });

                RetainableByteBuffer.Mutable buffer = server.getByteBufferPool().acquire(128 * 1024 * 1024, true);
                ByteBuffer byteBuffer = buffer.getByteBuffer();
                byteBuffer.clear();
                response.write(true, ReadableBuffer.wrap(byteBuffer), Callback.from(() ->
                {
                    serverWriteSuccessLatch.countDown();

                    // Release the buffer.
                    buffer.release();

                    // Complete the Handler callback.
                    callback.succeeded();
                }, x ->
                {
                    assertTrue(httpStreamCancelled.get());
                    serverWriteFailureLatch.countDown();

                    // Release the buffer.
                    buffer.release();

                    // Complete the Handler callback.
                    callback.failed(x);
                }));

                // Wait until TCP congestion.
                await().atMost(5, TimeUnit.SECONDS).until(() ->
                {
                    AbstractEndPoint endPoint = (AbstractEndPoint)request.getConnectionMetaData().getConnection().getEndPoint();
                    WriteFlusher flusher = endPoint.getWriteFlusher();
                    return flusher.isPending();
                });
                serverFlusherPendingLatch.countDown();

                return true;
            }
        });

        // Set the HTTP/2 flow control windows very large so we can
        // cause TCP congestion, not HTTP/2 flow control congestion.
        http2Client.setInitialSessionRecvWindow(512 * 1024 * 1024);
        http2Client.setInitialStreamRecvWindow(512 * 1024 * 1024);
        Session session = newClientSession(new Session.Listener() {});
        session.newStream(new HeadersFrame(newRequest("GET", HttpFields.EMPTY), null, true), new Stream.Listener()
        {
            @Override
            public void onHeaders(Stream stream, HeadersFrame frame, Callback callback)
            {
                try
                {
                    // Block to cause TCP congestion.
                    assertTrue(serverFlusherPendingLatch.await(5, TimeUnit.SECONDS));

                    stream.reset(new ResetFrame(stream.getId(), ErrorCode.CANCEL_STREAM_ERROR.code), callback);
                }
                catch (InterruptedException e)
                {
                    callback.failed(e);
                }
            }
        });

        assertTrue(serverWriteFailureLatch.await(5, TimeUnit.SECONDS));
        assertFalse(serverWriteSuccessLatch.await(1, TimeUnit.SECONDS));
    }
}
