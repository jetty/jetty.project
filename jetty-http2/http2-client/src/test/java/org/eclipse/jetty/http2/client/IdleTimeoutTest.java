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

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http2.api.Session;
import org.eclipse.jetty.http2.api.Stream;
import org.eclipse.jetty.http2.api.server.ServerSessionListener;
import org.eclipse.jetty.http2.frames.DataFrame;
import org.eclipse.jetty.http2.frames.GoAwayFrame;
import org.eclipse.jetty.http2.frames.HeadersFrame;
import org.eclipse.jetty.http2.frames.ResetFrame;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.Promise;
import org.junit.Assert;
import org.junit.Test;

import static org.hamcrest.core.IsInstanceOf.instanceOf;
import static org.junit.Assert.assertThat;

public class IdleTimeoutTest extends AbstractTest
{
    private final int idleTimeout = 1000;

    @Test
    public void testServerEnforcingIdleTimeout() throws Exception
    {
        startServer(new ServerSessionListener.Adapter()
        {
            @Override
            public Stream.Listener onNewStream(Stream stream, HeadersFrame requestFrame)
            {
                stream.setIdleTimeout(10 * idleTimeout);
                MetaData.Response metaData = new MetaData.Response(HttpVersion.HTTP_2, 200, new HttpFields());
                HeadersFrame responseFrame = new HeadersFrame(stream.getId(), metaData, null, true);
                stream.headers(responseFrame, Callback.Adapter.INSTANCE);
                return null;
            }
        });
        connector.setIdleTimeout(idleTimeout);

        final CountDownLatch latch = new CountDownLatch(1);
        Session session = newClient(new Session.Listener.Adapter()
        {
            @Override
            public void onClose(Session session, GoAwayFrame frame)
            {
                latch.countDown();
            }
        });

        MetaData.Request metaData = newRequest("GET", new HttpFields());
        HeadersFrame requestFrame = new HeadersFrame(0, metaData, null, true);
        session.newStream(requestFrame, new Promise.Adapter<Stream>()
        {
            @Override
            public void succeeded(Stream stream)
            {
                stream.setIdleTimeout(10 * idleTimeout);
            }
        }, new Stream.Listener.Adapter());

        Assert.assertTrue(latch.await(2 * idleTimeout, TimeUnit.MILLISECONDS));
    }

    @Test
    public void testServerEnforcingIdleTimeoutWithUnrespondedStream() throws Exception
    {
        startServer(new ServerSessionListener.Adapter()
        {
            @Override
            public Stream.Listener onNewStream(Stream stream, HeadersFrame frame)
            {
                stream.setIdleTimeout(10 * idleTimeout);
                return null;
            }
        });
        connector.setIdleTimeout(idleTimeout);

        final CountDownLatch latch = new CountDownLatch(1);
        Session session = newClient(new Session.Listener.Adapter()
        {
            @Override
            public void onClose(Session session, GoAwayFrame frame)
            {
                latch.countDown();
            }
        });

        // The request is not replied, and the server should idle timeout.
        MetaData.Request metaData = newRequest("GET", new HttpFields());
        HeadersFrame requestFrame = new HeadersFrame(0, metaData, null, true);
        session.newStream(requestFrame, new Promise.Adapter<Stream>()
        {
            @Override
            public void succeeded(Stream stream)
            {
                stream.setIdleTimeout(10 * idleTimeout);
            }
        }, new Stream.Listener.Adapter());

        Assert.assertTrue(latch.await(2 * idleTimeout, TimeUnit.MILLISECONDS));
    }

    @Test
    public void testServerNotEnforcingIdleTimeoutWithPendingStream() throws Exception
    {
        startServer(new ServerSessionListener.Adapter()
        {
            @Override
            public Stream.Listener onNewStream(Stream stream, HeadersFrame frame)
            {
                try
                {
                    stream.setIdleTimeout(10 * idleTimeout);
                    Thread.sleep(2 * idleTimeout);
                    MetaData.Response metaData = new MetaData.Response(HttpVersion.HTTP_2, 200, new HttpFields());
                    HeadersFrame responseFrame = new HeadersFrame(stream.getId(), metaData, null, true);
                    stream.headers(responseFrame, Callback.Adapter.INSTANCE);
                    return null;
                }
                catch (InterruptedException x)
                {
                    Assert.fail();
                    return null;
                }
            }
        });
        connector.setIdleTimeout(idleTimeout);

        final CountDownLatch closeLatch = new CountDownLatch(1);
        Session session = newClient(new ServerSessionListener.Adapter()
        {
            @Override
            public void onClose(Session session, GoAwayFrame frame)
            {
                closeLatch.countDown();
            }
        });

        final CountDownLatch replyLatch = new CountDownLatch(1);
        MetaData.Request metaData = newRequest("GET", new HttpFields());
        HeadersFrame requestFrame = new HeadersFrame(0, metaData, null, true);
        session.newStream(requestFrame, new Promise.Adapter<Stream>()
        {
            @Override
            public void succeeded(Stream stream)
            {
                stream.setIdleTimeout(10 * idleTimeout);
            }
        }, new Stream.Listener.Adapter()
        {
            @Override
            public void onHeaders(Stream stream, HeadersFrame frame)
            {
                replyLatch.countDown();
            }
        });

        Assert.assertTrue(replyLatch.await(3 * idleTimeout, TimeUnit.MILLISECONDS));

        // Just make sure onClose() has never been called, but don't wait too much
        Assert.assertFalse(closeLatch.await(idleTimeout / 2, TimeUnit.MILLISECONDS));
    }

    @Test
    public void testClientEnforcingIdleTimeout() throws Exception
    {
        final CountDownLatch closeLatch = new CountDownLatch(1);
        startServer(new ServerSessionListener.Adapter()
        {
            @Override
            public Stream.Listener onNewStream(Stream stream, HeadersFrame frame)
            {
                stream.setIdleTimeout(10 * idleTimeout);
                MetaData.Response metaData = new MetaData.Response(HttpVersion.HTTP_2, 200, new HttpFields());
                HeadersFrame responseFrame = new HeadersFrame(stream.getId(), metaData, null, true);
                stream.headers(responseFrame, Callback.Adapter.INSTANCE);
                return null;
            }

            @Override
            public void onClose(Session session, GoAwayFrame frame)
            {
                closeLatch.countDown();
            }
        });
        client.setIdleTimeout(idleTimeout);

        Session session = newClient(new Session.Listener.Adapter());
        MetaData.Request metaData = newRequest("GET", new HttpFields());
        HeadersFrame requestFrame = new HeadersFrame(0, metaData, null, true);
        session.newStream(requestFrame, new Promise.Adapter<Stream>()
        {
            @Override
            public void succeeded(Stream stream)
            {
                stream.setIdleTimeout(10 * idleTimeout);
            }
        }, new Stream.Listener.Adapter());

        Assert.assertTrue(closeLatch.await(2 * idleTimeout, TimeUnit.MILLISECONDS));
    }

    @Test
    public void testClientEnforcingIdleTimeoutWithUnrespondedStream() throws Exception
    {
        final CountDownLatch closeLatch = new CountDownLatch(1);
        startServer(new ServerSessionListener.Adapter()
        {
            @Override
            public Stream.Listener onNewStream(Stream stream, HeadersFrame frame)
            {
                stream.setIdleTimeout(10 * idleTimeout);
                return null;
            }

            @Override
            public void onClose(Session session, GoAwayFrame frame)
            {
                closeLatch.countDown();
            }
        });
        client.setIdleTimeout(idleTimeout);

        Session session = newClient(new Session.Listener.Adapter());
        MetaData.Request metaData = newRequest("GET", new HttpFields());
        HeadersFrame requestFrame = new HeadersFrame(0, metaData, null, true);
        session.newStream(requestFrame, new Promise.Adapter<Stream>()
        {
            @Override
            public void succeeded(Stream stream)
            {
                stream.setIdleTimeout(10 * idleTimeout);
            }
        }, new Stream.Listener.Adapter());

        Assert.assertTrue(closeLatch.await(2 * idleTimeout, TimeUnit.MILLISECONDS));
    }

    @Test
    public void testClientNotEnforcingIdleTimeoutWithPendingStream() throws Exception
    {
        final CountDownLatch closeLatch = new CountDownLatch(1);
        startServer(new ServerSessionListener.Adapter()
        {
            @Override
            public Stream.Listener onNewStream(Stream stream, HeadersFrame frame)
            {
                stream.setIdleTimeout(10 * idleTimeout);
                MetaData.Response metaData = new MetaData.Response(HttpVersion.HTTP_2, 200, new HttpFields());
                HeadersFrame responseFrame = new HeadersFrame(stream.getId(), metaData, null, true);
                stream.headers(responseFrame, Callback.Adapter.INSTANCE);
                return null;
            }

            @Override
            public void onClose(Session session, GoAwayFrame frame)
            {
                closeLatch.countDown();
            }
        });
        client.setIdleTimeout(idleTimeout);

        Session session = newClient(new Session.Listener.Adapter());

        final CountDownLatch replyLatch = new CountDownLatch(1);
        MetaData.Request metaData = newRequest("GET", new HttpFields());
        HeadersFrame requestFrame = new HeadersFrame(0, metaData, null, true);
        session.newStream(requestFrame, new Promise.Adapter<Stream>()
        {
            @Override
            public void succeeded(Stream stream)
            {
                stream.setIdleTimeout(10 * idleTimeout);
            }
        }, new Stream.Listener.Adapter()
        {
            @Override
            public void onHeaders(Stream stream, HeadersFrame frame)
            {
                try
                {
                    Thread.sleep(2 * idleTimeout);
                    replyLatch.countDown();
                }
                catch (InterruptedException e)
                {
                    Assert.fail();
                }
            }
        });

        Assert.assertFalse(closeLatch.await(2 * idleTimeout, TimeUnit.MILLISECONDS));
        Assert.assertTrue(replyLatch.await(3 * idleTimeout, TimeUnit.MILLISECONDS));
    }

    @Test
    public void testClientEnforcingStreamIdleTimeout() throws Exception
    {
        final int idleTimeout = 1000;
        startServer(new HttpServlet()
        {
            @Override
            protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException
            {
                try
                {
                    Thread.sleep(2 * idleTimeout);
                }
                catch (InterruptedException x)
                {
                    throw new RuntimeException(x);
                }
            }
        });

        Session session = newClient(new Session.Listener.Adapter());

        final CountDownLatch dataLatch = new CountDownLatch(1);
        final CountDownLatch timeoutLatch = new CountDownLatch(1);
        MetaData.Request metaData = newRequest("GET", new HttpFields());
        HeadersFrame requestFrame = new HeadersFrame(0, metaData, null, true);
        session.newStream(requestFrame, new Promise.Adapter<Stream>()
        {
            @Override
            public void succeeded(Stream stream)
            {
                stream.setIdleTimeout(idleTimeout);
            }
        }, new Stream.Listener.Adapter()
        {
            @Override
            public void onData(Stream stream, DataFrame frame, Callback callback)
            {
                dataLatch.countDown();
            }

            @Override
            public void onFailure(Stream stream, Throwable x)
            {
                assertThat(x, instanceOf(TimeoutException.class));
                timeoutLatch.countDown();
            }
        });

        Assert.assertTrue(timeoutLatch.await(5, TimeUnit.SECONDS));
        // We must not receive any DATA frame.
        Assert.assertFalse(dataLatch.await(2 * idleTimeout, TimeUnit.MILLISECONDS));
        // Stream must be gone.
        Assert.assertTrue(session.getStreams().isEmpty());
    }

    @Test
    public void testServerEnforcingStreamIdleTimeout() throws Exception
    {
        final CountDownLatch timeoutLatch = new CountDownLatch(1);
        startServer(new ServerSessionListener.Adapter()
        {
            @Override
            public Stream.Listener onNewStream(Stream stream, HeadersFrame frame)
            {
                stream.setIdleTimeout(idleTimeout);
                return new Stream.Listener.Adapter()
                {
                    @Override
                    public void onFailure(Stream stream, Throwable x)
                    {
                        timeoutLatch.countDown();
                    }
                };
            }
        });

        final CountDownLatch resetLatch = new CountDownLatch(1);
        Session session = newClient(new Session.Listener.Adapter()
        {
            @Override
            public void onReset(Session session, ResetFrame frame)
            {
                resetLatch.countDown();
            }
        });
        MetaData.Request metaData = newRequest("GET", new HttpFields());
        // Stream does not end here, but we won't send any DATA frame.
        HeadersFrame requestFrame = new HeadersFrame(0, metaData, null, false);
        session.newStream(requestFrame, new Promise.Adapter<Stream>(), new Stream.Listener.Adapter());

        Assert.assertTrue(timeoutLatch.await(5, TimeUnit.SECONDS));
        Assert.assertTrue(resetLatch.await(5, TimeUnit.SECONDS));
        // Stream must be gone.
        Assert.assertTrue(session.getStreams().isEmpty());
    }

    @Test
    public void testStreamIdleTimeoutIsNotEnforcedWhenReceiving() throws Exception
    {
        final CountDownLatch timeoutLatch = new CountDownLatch(1);
        startServer(new ServerSessionListener.Adapter()
        {
            @Override
            public Stream.Listener onNewStream(Stream stream, HeadersFrame frame)
            {
                stream.setIdleTimeout(idleTimeout);
                return new Stream.Listener.Adapter()
                {
                    @Override
                    public void onFailure(Stream stream, Throwable x)
                    {
                        timeoutLatch.countDown();
                    }
                };
            }
        });

        Session session = newClient(new Session.Listener.Adapter());
        MetaData.Request metaData = newRequest("GET", new HttpFields());
        HeadersFrame requestFrame = new HeadersFrame(0, metaData, null, false);
        session.newStream(requestFrame, new Promise.Adapter<Stream>()
        {
            @Override
            public void succeeded(final Stream stream)
            {
                sleep(idleTimeout / 2);
                stream.data(new DataFrame(stream.getId(), ByteBuffer.allocate(1), false), new Callback.Adapter()
                {
                    private int sends;

                    @Override
                    public void succeeded()
                    {
                        sleep(idleTimeout / 2);
                        boolean last = ++sends == 2;
                        stream.data(new DataFrame(stream.getId(), ByteBuffer.allocate(1), last), last ? INSTANCE : this);
                    }
                });
            }
        }, new Stream.Listener.Adapter());

        Assert.assertFalse(timeoutLatch.await(1, TimeUnit.SECONDS));
    }

    private void sleep(long value)
    {
        try
        {
            TimeUnit.MILLISECONDS.sleep(value);
        }
        catch (InterruptedException x)
        {
            Assert.fail();
        }
    }
}
