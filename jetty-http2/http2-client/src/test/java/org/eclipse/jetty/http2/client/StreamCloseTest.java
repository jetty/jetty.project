//
//  ========================================================================
//  Copyright (c) 1995-2015 Mort Bay Consulting Pty. Ltd.
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

import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http2.HTTP2Stream;
import org.eclipse.jetty.http2.api.Session;
import org.eclipse.jetty.http2.api.Stream;
import org.eclipse.jetty.http2.api.server.ServerSessionListener;
import org.eclipse.jetty.http2.frames.DataFrame;
import org.eclipse.jetty.http2.frames.HeadersFrame;
import org.eclipse.jetty.http2.frames.PushPromiseFrame;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.FuturePromise;
import org.eclipse.jetty.util.Promise;
import org.junit.Assert;
import org.junit.Test;

public class StreamCloseTest extends AbstractTest
{
    @Test
    public void testRequestClosedRemotelyClosesStream() throws Exception
    {
        final CountDownLatch latch = new CountDownLatch(1);
        start(new ServerSessionListener.Adapter()
        {
            @Override
            public Stream.Listener onNewStream(Stream stream, HeadersFrame frame)
            {
                Assert.assertTrue(((HTTP2Stream)stream).isRemotelyClosed());
                latch.countDown();
                return null;
            }
        });

        Session session = newClient(new Session.Listener.Adapter());
        HeadersFrame frame = new HeadersFrame(0, newRequest("GET", new HttpFields()), null, true);
        FuturePromise<Stream> promise = new FuturePromise<>();
        session.newStream(frame, promise, null);
        Stream stream = promise.get(5, TimeUnit.SECONDS);
        Assert.assertTrue(((HTTP2Stream)stream).isLocallyClosed());
        Assert.assertTrue(latch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testRequestClosedResponseClosedClosesStream() throws Exception
    {
        final CountDownLatch latch = new CountDownLatch(2);
        start(new ServerSessionListener.Adapter()
        {
            @Override
            public Stream.Listener onNewStream(final Stream stream, HeadersFrame frame)
            {
                MetaData.Response metaData = new MetaData.Response(HttpVersion.HTTP_2, 200, new HttpFields());
                HeadersFrame response = new HeadersFrame(stream.getId(), metaData, null, true);
                stream.headers(response, new Callback.Adapter()
                {
                    @Override
                    public void succeeded()
                    {
                        Assert.assertTrue(stream.isClosed());
                        Assert.assertEquals(0, stream.getSession().getStreams().size());
                        latch.countDown();
                    }
                });
                return null;
            }
        });

        Session session = newClient(new Session.Listener.Adapter());
        HeadersFrame frame = new HeadersFrame(0, newRequest("GET", new HttpFields()), null, true);
        session.newStream(frame, new Promise.Adapter<Stream>(), new Stream.Listener.Adapter()
        {
            @Override
            public void onHeaders(Stream stream, HeadersFrame frame)
            {
                Assert.assertTrue(stream.isClosed());
                latch.countDown();
            }
        });
        Assert.assertTrue(latch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testRequestDataClosedResponseDataClosedClosesStream() throws Exception
    {
        final CountDownLatch serverDataLatch = new CountDownLatch(1);
        start(new ServerSessionListener.Adapter()
        {
            @Override
            public Stream.Listener onNewStream(Stream stream, HeadersFrame frame)
            {
                MetaData.Response metaData = new MetaData.Response(HttpVersion.HTTP_2, 200, new HttpFields());
                HeadersFrame response = new HeadersFrame(stream.getId(), metaData, null, false);
                stream.headers(response, Callback.Adapter.INSTANCE);
                return new Stream.Listener.Adapter()
                {
                    @Override
                    public void onData(final Stream stream, DataFrame frame, final Callback callback)
                    {
                        Assert.assertTrue(((HTTP2Stream)stream).isRemotelyClosed());
                        stream.data(frame, new Callback.Adapter()
                        {
                            @Override
                            public void succeeded()
                            {
                                Assert.assertTrue(stream.isClosed());
                                Assert.assertEquals(0, stream.getSession().getStreams().size());
                                callback.succeeded();
                                serverDataLatch.countDown();
                            }
                        });
                    }
                };
            }
        });

        final CountDownLatch completeLatch = new CountDownLatch(1);
        Session session = newClient(new Session.Listener.Adapter());
        HeadersFrame frame = new HeadersFrame(0, newRequest("GET", new HttpFields()), null, false);
        FuturePromise<Stream> promise = new FuturePromise<>();
        session.newStream(frame, promise, new Stream.Listener.Adapter()
        {
            @Override
            public void onData(Stream stream, DataFrame frame, Callback callback)
            {
                Assert.assertTrue(stream.isClosed());
                completeLatch.countDown();
            }
        });
        final Stream stream = promise.get(5, TimeUnit.SECONDS);
        Assert.assertFalse(stream.isClosed());
        Assert.assertFalse(((HTTP2Stream)stream).isLocallyClosed());

        final CountDownLatch clientDataLatch = new CountDownLatch(1);
        stream.data(new DataFrame(stream.getId(), ByteBuffer.wrap(new byte[512]), true), new Callback.Adapter()
        {
            @Override
            public void succeeded()
            {
                Assert.assertTrue(((HTTP2Stream)stream).isLocallyClosed());
                clientDataLatch.countDown();
            }
        });

        Assert.assertTrue(clientDataLatch.await(5, TimeUnit.SECONDS));
        Assert.assertTrue(serverDataLatch.await(5, TimeUnit.SECONDS));
        Assert.assertTrue(completeLatch.await(5, TimeUnit.SECONDS));
        Assert.assertEquals(0, stream.getSession().getStreams().size());
    }

    @Test
    public void testPushedStreamIsClosed() throws Exception
    {
        final CountDownLatch serverLatch = new CountDownLatch(1);
        start(new ServerSessionListener.Adapter()
        {
            @Override
            public Stream.Listener onNewStream(Stream stream, HeadersFrame frame)
            {
                PushPromiseFrame pushFrame = new PushPromiseFrame(stream.getId(), 0, newRequest("GET", new HttpFields()));
                stream.push(pushFrame, new Promise.Adapter<Stream>()
                {
                    @Override
                    public void succeeded(final Stream pushedStream)
                    {
                        // When created, pushed stream must be implicitly remotely closed.
                        Assert.assertTrue(((HTTP2Stream)pushedStream).isRemotelyClosed());
                        // Send some data with endStream = true.
                        pushedStream.data(new DataFrame(pushedStream.getId(), ByteBuffer.allocate(16), true), new Callback.Adapter()
                        {
                            @Override
                            public void succeeded()
                            {
                                Assert.assertTrue(pushedStream.isClosed());
                                serverLatch.countDown();
                            }
                        });
                    }
                });
                HeadersFrame response = new HeadersFrame(stream.getId(), new MetaData.Response(HttpVersion.HTTP_2, 200, new HttpFields()), null, true);
                stream.headers(response, Callback.Adapter.INSTANCE);
                return null;
            }
        });

        Session session = newClient(new Session.Listener.Adapter());
        HeadersFrame frame = new HeadersFrame(0, newRequest("GET", new HttpFields()), null, false);
        Promise<Stream> promise = new Promise.Adapter<>();
        final CountDownLatch clientLatch = new CountDownLatch(1);
        session.newStream(frame, promise, new Stream.Listener.Adapter()
        {
            @Override
            public Stream.Listener onPush(Stream pushedStream, PushPromiseFrame frame)
            {
                Assert.assertTrue(((HTTP2Stream)pushedStream).isLocallyClosed());
                return new Adapter()
                {
                    @Override
                    public void onData(Stream pushedStream, DataFrame frame, Callback callback)
                    {
                        Assert.assertTrue(pushedStream.isClosed());
                        clientLatch.countDown();
                    }
                };
            }
        });

        Assert.assertTrue(serverLatch.await(5, TimeUnit.SECONDS));
        Assert.assertTrue(clientLatch.await(5, TimeUnit.SECONDS));
    }
}
