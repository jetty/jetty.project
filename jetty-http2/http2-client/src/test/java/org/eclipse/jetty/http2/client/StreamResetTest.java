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

package org.eclipse.jetty.http2.client;

import java.nio.ByteBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.http.FinalMetaData;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http2.api.Session;
import org.eclipse.jetty.http2.api.Stream;
import org.eclipse.jetty.http2.api.server.ServerSessionListener;
import org.eclipse.jetty.http2.frames.DataFrame;
import org.eclipse.jetty.http2.frames.HeadersFrame;
import org.eclipse.jetty.http2.frames.ResetFrame;
import org.eclipse.jetty.http2.parser.ErrorCode;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.FuturePromise;
import org.junit.Assert;
import org.junit.Test;

public class StreamResetTest extends AbstractTest
{
    @Test
    public void testStreamSendingResetIsRemoved() throws Exception
    {
        startServer(new ServerSessionListener.Adapter());

        Session client = newClient(new Session.Listener.Adapter());
        MetaData.Request request = newRequest("GET", new HttpFields());
        HeadersFrame requestFrame = new HeadersFrame(0, request, null, false);
        FuturePromise<Stream> promise = new FuturePromise<>();
        client.newStream(requestFrame, promise, new Stream.Listener.Adapter());
        Stream stream = promise.get(5, TimeUnit.SECONDS);
        ResetFrame resetFrame = new ResetFrame(stream.getId(), ErrorCode.CANCEL_STREAM_ERROR);
        stream.getSession().reset(resetFrame, Callback.Adapter.INSTANCE);
        // After reset the stream should be gone.
        Assert.assertEquals(0, client.getStreams().size());
    }

    @Test
    public void testStreamReceivingResetIsRemoved() throws Exception
    {
        final AtomicReference<Stream> streamRef = new AtomicReference<>();
        final CountDownLatch resetLatch = new CountDownLatch(1);
        startServer(new ServerSessionListener.Adapter()
        {
            @Override
            public void onReset(Session session, ResetFrame frame)
            {
                Stream stream = session.getStream(frame.getStreamId());
                Assert.assertNotNull(stream);
                Assert.assertTrue(stream.isReset());
                streamRef.set(stream);
                resetLatch.countDown();
            }
        });

        Session client = newClient(new Session.Listener.Adapter());
        MetaData.Request request = newRequest("GET", new HttpFields());
        HeadersFrame requestFrame = new HeadersFrame(0, request, null, false);
        FuturePromise<Stream> promise = new FuturePromise<>();
        client.newStream(requestFrame, promise, new Stream.Listener.Adapter());
        Stream stream = promise.get(5, TimeUnit.SECONDS);
        ResetFrame resetFrame = new ResetFrame(stream.getId(), ErrorCode.CANCEL_STREAM_ERROR);
        stream.getSession().reset(resetFrame, Callback.Adapter.INSTANCE);

        Assert.assertTrue(resetLatch.await(5, TimeUnit.SECONDS));
        Stream serverStream = streamRef.get();
        Assert.assertEquals(0, serverStream.getSession().getStreams().size());
    }

    @Test
    public void testStreamResetDoesNotCloseConnection() throws Exception
    {
        final CountDownLatch serverResetLatch = new CountDownLatch(1);
        final CountDownLatch serverDataLatch = new CountDownLatch(1);
        startServer(new ServerSessionListener.Adapter()
        {
            @Override
            public Stream.Listener onNewStream(Stream stream, HeadersFrame requestFrame)
            {
                MetaData.Response response = new FinalMetaData.Response(HttpVersion.HTTP_2, 200, new HttpFields());
                HeadersFrame responseFrame = new HeadersFrame(stream.getId(), response, null, false);
                stream.headers(responseFrame, Callback.Adapter.INSTANCE);
                return new Stream.Listener.Adapter()
                {
                    @Override
                    public void onData(Stream stream, DataFrame frame, Callback callback)
                    {
                        callback.succeeded();
                        stream.data(new DataFrame(stream.getId(), ByteBuffer.allocate(16), true), Callback.Adapter.INSTANCE);
                        serverDataLatch.countDown();
                    }
                };
            }

            @Override
            public void onReset(Session session, ResetFrame frame)
            {
                Stream stream = session.getStream(frame.getStreamId());
                // Simulate that there is pending data to send.
                stream.data(new DataFrame(stream.getId(), ByteBuffer.allocate(16), true), new Callback.Adapter()
                {
                    @Override
                    public void failed(Throwable x)
                    {
                        serverResetLatch.countDown();
                    }
                });
            }
        });

        Session client = newClient(new Session.Listener.Adapter());
        MetaData.Request request1 = newRequest("GET", new HttpFields());
        HeadersFrame requestFrame1 = new HeadersFrame(0, request1, null, false);
        FuturePromise<Stream> promise1 = new FuturePromise<>();
        final CountDownLatch stream1HeadersLatch = new CountDownLatch(1);
        final CountDownLatch stream1DataLatch = new CountDownLatch(1);
        client.newStream(requestFrame1, promise1, new Stream.Listener.Adapter()
        {
            @Override
            public void onHeaders(Stream stream, HeadersFrame frame)
            {
                stream1HeadersLatch.countDown();
            }

            @Override
            public void onData(Stream stream, DataFrame frame, Callback callback)
            {
                stream1DataLatch.countDown();
            }
        });
        Stream stream1 = promise1.get(5, TimeUnit.SECONDS);
        Assert.assertTrue(stream1HeadersLatch.await(5, TimeUnit.SECONDS));

        MetaData.Request request2 = newRequest("GET", new HttpFields());
        HeadersFrame requestFrame2 = new HeadersFrame(0, request2, null, false);
        FuturePromise<Stream> promise2 = new FuturePromise<>();
        final CountDownLatch stream2DataLatch = new CountDownLatch(1);
        client.newStream(requestFrame2, promise2, new Stream.Listener.Adapter()
        {
            @Override
            public void onData(Stream stream, DataFrame frame, Callback callback)
            {
                stream2DataLatch.countDown();
            }
        });
        Stream stream2 = promise2.get(5, TimeUnit.SECONDS);

        ResetFrame resetFrame = new ResetFrame(stream1.getId(), ErrorCode.CANCEL_STREAM_ERROR);
        stream1.getSession().reset(resetFrame, Callback.Adapter.INSTANCE);

        Assert.assertTrue(serverResetLatch.await(5, TimeUnit.SECONDS));
        // Stream MUST NOT receive data sent by server after reset.
        Assert.assertFalse(stream1DataLatch.await(1, TimeUnit.SECONDS));

        // The other stream should still be working.
        stream2.data(new DataFrame(stream2.getId(), ByteBuffer.allocate(16), true), Callback.Adapter.INSTANCE);
        Assert.assertTrue(serverDataLatch.await(5, TimeUnit.SECONDS));
        Assert.assertTrue(stream2DataLatch.await(5, TimeUnit.SECONDS));
    }
}
