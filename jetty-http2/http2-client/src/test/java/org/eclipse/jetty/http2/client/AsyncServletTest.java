//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.servlet.AsyncContext;
import javax.servlet.AsyncEvent;
import javax.servlet.AsyncListener;
import javax.servlet.ServletException;
import javax.servlet.ServletOutputStream;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http2.ErrorCode;
import org.eclipse.jetty.http2.api.Session;
import org.eclipse.jetty.http2.api.Stream;
import org.eclipse.jetty.http2.api.server.ServerSessionListener;
import org.eclipse.jetty.http2.frames.DataFrame;
import org.eclipse.jetty.http2.frames.HeadersFrame;
import org.eclipse.jetty.http2.frames.ResetFrame;
import org.eclipse.jetty.http2.server.HTTP2ServerConnectionFactory;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.FuturePromise;
import org.eclipse.jetty.util.Promise;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class AsyncServletTest extends AbstractTest
{
    @Test
    public void testStartAsyncThenDispatch() throws Exception
    {
        byte[] content = new byte[1024];
        new Random().nextBytes(content);
        start(new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
            {
                AsyncContext asyncContext = (AsyncContext)request.getAttribute(AsyncContext.class.getName());
                if (asyncContext == null)
                {
                    AsyncContext context = request.startAsync();
                    context.setTimeout(0);
                    request.setAttribute(AsyncContext.class.getName(), context);
                    context.start(() ->
                    {
                        sleep(1000);
                        context.dispatch();
                    });
                }
                else
                {
                    response.getOutputStream().write(content);
                }
            }
        });

        Session session = newClient(new Session.Listener.Adapter());

        HttpFields fields = new HttpFields();
        MetaData.Request metaData = newRequest("GET", fields);
        HeadersFrame frame = new HeadersFrame(metaData, null, true);
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        CountDownLatch latch = new CountDownLatch(1);
        session.newStream(frame, new Promise.Adapter<>(), new Stream.Listener.Adapter()
        {
            @Override
            public void onData(Stream stream, DataFrame frame, Callback callback)
            {
                try
                {
                    BufferUtil.writeTo(frame.getData(), buffer);
                    callback.succeeded();
                    if (frame.isEndStream())
                        latch.countDown();
                }
                catch (IOException x)
                {
                    callback.failed(x);
                }
            }
        });

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertArrayEquals(content, buffer.toByteArray());
    }

    @Test
    public void testStartAsyncThenClientSessionIdleTimeout() throws Exception
    {
        CountDownLatch serverLatch = new CountDownLatch(1);
        start(new AsyncOnErrorServlet(serverLatch));
        long idleTimeout = 1000;
        client.setIdleTimeout(idleTimeout);

        Session session = newClient(new Session.Listener.Adapter());
        HttpFields fields = new HttpFields();
        MetaData.Request metaData = newRequest("GET", fields);
        HeadersFrame frame = new HeadersFrame(metaData, null, true);
        FuturePromise<Stream> promise = new FuturePromise<>();
        CountDownLatch responseLatch = new CountDownLatch(1);
        CountDownLatch failLatch = new CountDownLatch(1);
        session.newStream(frame, promise, new Stream.Listener.Adapter()
        {
            @Override
            public void onHeaders(Stream stream, HeadersFrame frame)
            {
                responseLatch.countDown();
            }

            @Override
            public void onFailure(Stream stream, int error, String reason, Throwable failure, Callback callback)
            {
                failLatch.countDown();
                callback.succeeded();
            }
        });
        Stream stream = promise.get(5, TimeUnit.SECONDS);
        stream.setIdleTimeout(10 * idleTimeout);

        assertTrue(serverLatch.await(2 * idleTimeout, TimeUnit.MILLISECONDS));
        assertFalse(responseLatch.await(idleTimeout + 1000, TimeUnit.MILLISECONDS));
        assertTrue(failLatch.await(2 * idleTimeout, TimeUnit.MILLISECONDS));
    }

    @Test
    public void testStartAsyncThenClientStreamIdleTimeout() throws Exception
    {
        CountDownLatch serverLatch = new CountDownLatch(1);
        start(new AsyncOnErrorServlet(serverLatch));
        long idleTimeout = 1000;
        client.setIdleTimeout(10 * idleTimeout);

        Session session = newClient(new Session.Listener.Adapter());
        HttpFields fields = new HttpFields();
        MetaData.Request metaData = newRequest("GET", fields);
        HeadersFrame frame = new HeadersFrame(metaData, null, true);
        FuturePromise<Stream> promise = new FuturePromise<>();
        CountDownLatch clientLatch = new CountDownLatch(1);
        session.newStream(frame, promise, new Stream.Listener.Adapter()
        {
            @Override
            public boolean onIdleTimeout(Stream stream, Throwable x)
            {
                clientLatch.countDown();
                return true;
            }
        });
        Stream stream = promise.get(5, TimeUnit.SECONDS);
        stream.setIdleTimeout(idleTimeout);

        // When the client resets, the server receives the
        // corresponding frame and acts by notifying the failure,
        // but the response is not sent back to the client.
        assertTrue(serverLatch.await(2 * idleTimeout, TimeUnit.MILLISECONDS));
        assertTrue(clientLatch.await(2 * idleTimeout, TimeUnit.MILLISECONDS));
    }

    @Test
    public void testStartAsyncThenClientResetWithoutRemoteErrorNotification() throws Exception
    {
        HttpConfiguration httpConfiguration = new HttpConfiguration();
        httpConfiguration.setNotifyRemoteAsyncErrors(false);
        prepareServer(new HTTP2ServerConnectionFactory(httpConfiguration));
        ServletContextHandler context = new ServletContextHandler(server, "/");
        AtomicReference<AsyncContext> asyncContextRef = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        context.addServlet(new ServletHolder(new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
            {
                AsyncContext asyncContext = request.startAsync();
                asyncContext.setTimeout(0);
                asyncContextRef.set(asyncContext);
                latch.countDown();
            }
        }), servletPath + "/*");
        server.start();

        prepareClient();
        client.start();
        Session session = newClient(new Session.Listener.Adapter());
        HttpFields fields = new HttpFields();
        MetaData.Request metaData = newRequest("GET", fields);
        HeadersFrame frame = new HeadersFrame(metaData, null, true);
        FuturePromise<Stream> promise = new FuturePromise<>();
        session.newStream(frame, promise, new Stream.Listener.Adapter());
        Stream stream = promise.get(5, TimeUnit.SECONDS);

        // Wait for the server to be in ASYNC_WAIT.
        assertTrue(latch.await(5, TimeUnit.SECONDS));
        sleep(500);

        stream.reset(new ResetFrame(stream.getId(), ErrorCode.CANCEL_STREAM_ERROR.code), Callback.NOOP);

        // Wait for the reset to be processed by the server.
        sleep(500);

        AsyncContext asyncContext = asyncContextRef.get();
        ServletResponse response = asyncContext.getResponse();
        ServletOutputStream output = response.getOutputStream();

        assertThrows(IOException.class,
            () ->
            {
                // Large writes or explicit flush() must
                // fail because the stream has been reset.
                output.flush();
            });
    }

    @Test
    public void testStartAsyncThenServerSessionIdleTimeout() throws Exception
    {
        testStartAsyncThenServerIdleTimeout(1000, 10 * 1000);
    }

    @Test
    public void testStartAsyncThenServerStreamIdleTimeout() throws Exception
    {
        testStartAsyncThenServerIdleTimeout(10 * 1000, 1000);
    }

    private void testStartAsyncThenServerIdleTimeout(long sessionTimeout, long streamTimeout) throws Exception
    {
        prepareServer(new HTTP2ServerConnectionFactory(new HttpConfiguration())
        {
            @Override
            protected ServerSessionListener newSessionListener(Connector connector, EndPoint endPoint)
            {
                return new HTTPServerSessionListener(connector, endPoint)
                {
                    @Override
                    public Stream.Listener onNewStream(Stream stream, HeadersFrame frame)
                    {
                        stream.setIdleTimeout(streamTimeout);
                        return super.onNewStream(stream, frame);
                    }
                };
            }
        });
        connector.setIdleTimeout(sessionTimeout);
        ServletContextHandler context = new ServletContextHandler(server, "/");
        long timeout = Math.min(sessionTimeout, streamTimeout);
        CountDownLatch errorLatch = new CountDownLatch(1);
        context.addServlet(new ServletHolder(new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
            {
                AsyncContext asyncContext = (AsyncContext)request.getAttribute(AsyncContext.class.getName());
                if (asyncContext == null)
                {
                    AsyncContext context = request.startAsync();
                    context.setTimeout(2 * timeout);
                    request.setAttribute(AsyncContext.class.getName(), context);
                    context.addListener(new AsyncListener()
                    {
                        @Override
                        public void onComplete(AsyncEvent event) throws IOException
                        {
                        }

                        @Override
                        public void onTimeout(AsyncEvent event) throws IOException
                        {
                            event.getAsyncContext().complete();
                        }

                        @Override
                        public void onError(AsyncEvent event) throws IOException
                        {
                            errorLatch.countDown();
                        }

                        @Override
                        public void onStartAsync(AsyncEvent event) throws IOException
                        {
                        }
                    });
                }
                else
                {
                    throw new ServletException();
                }
            }
        }), servletPath + "/*");
        server.start();

        prepareClient();
        client.start();

        Session session = newClient(new Session.Listener.Adapter());
        HttpFields fields = new HttpFields();
        MetaData.Request metaData = newRequest("GET", fields);
        HeadersFrame frame = new HeadersFrame(metaData, null, true);
        CountDownLatch clientLatch = new CountDownLatch(1);
        session.newStream(frame, new Promise.Adapter<>(), new Stream.Listener.Adapter()
        {
            @Override
            public void onHeaders(Stream stream, HeadersFrame frame)
            {
                MetaData.Response response = (MetaData.Response)frame.getMetaData();
                if (response.getStatus() == HttpStatus.OK_200 && frame.isEndStream())
                    clientLatch.countDown();
            }
        });

        // When the server idle times out, but the request has been dispatched
        // then the server must ignore the idle timeout as per Servlet semantic.
        assertFalse(errorLatch.await(2 * timeout, TimeUnit.MILLISECONDS));
        assertTrue(clientLatch.await(2 * timeout, TimeUnit.MILLISECONDS));
    }

    private void sleep(long ms)
    {
        try
        {
            Thread.sleep(ms);
        }
        catch (InterruptedException x)
        {
            x.printStackTrace();
        }
    }

    private static class AsyncOnErrorServlet extends HttpServlet implements AsyncListener
    {
        private final CountDownLatch latch;

        public AsyncOnErrorServlet(CountDownLatch latch)
        {
            this.latch = latch;
        }

        @Override
        protected void service(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
        {
            AsyncContext asyncContext = (AsyncContext)request.getAttribute(AsyncContext.class.getName());
            if (asyncContext == null)
            {
                AsyncContext context = request.startAsync();
                context.setTimeout(0);
                request.setAttribute(AsyncContext.class.getName(), context);
                context.addListener(this);
            }
            else
            {
                throw new ServletException();
            }
        }

        @Override
        public void onComplete(AsyncEvent event) throws IOException
        {
        }

        @Override
        public void onTimeout(AsyncEvent event) throws IOException
        {
        }

        @Override
        public void onError(AsyncEvent event) throws IOException
        {
            HttpServletResponse response = (HttpServletResponse)event.getSuppliedResponse();
            response.setStatus(HttpStatus.INTERNAL_SERVER_ERROR_500);
            event.getAsyncContext().complete();
            latch.countDown();
        }

        @Override
        public void onStartAsync(AsyncEvent event) throws IOException
        {
        }
    }
}
