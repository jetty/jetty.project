//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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

import javax.servlet.AsyncContext;
import javax.servlet.AsyncEvent;
import javax.servlet.AsyncListener;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http2.api.Session;
import org.eclipse.jetty.http2.api.Stream;
import org.eclipse.jetty.http2.api.server.ServerSessionListener;
import org.eclipse.jetty.http2.frames.DataFrame;
import org.eclipse.jetty.http2.frames.HeadersFrame;
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
import org.junit.Assert;
import org.junit.Test;

public class AsyncServletTest extends AbstractTest
{
    @Test
    public void testAsyncContextWithDispatch() throws Exception
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
                    buffer.write(BufferUtil.toArray(frame.getData()));
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

        Assert.assertTrue(latch.await(5, TimeUnit.SECONDS));
        Assert.assertArrayEquals(content, buffer.toByteArray());
    }

    @Test
    public void testAsyncContextWithClientSessionIdleTimeout() throws Exception
    {
        testAsyncContextWithClientIdleTimeout(1000, 10 * 1000);
    }

    @Test
    public void testAsyncContextWithClientStreamIdleTimeout() throws Exception
    {
        testAsyncContextWithClientIdleTimeout(10 * 1000, 1000);
    }

    private void testAsyncContextWithClientIdleTimeout(long sessionTimeout, long streamTimeout) throws Exception
    {
        CountDownLatch serverLatch = new CountDownLatch(1);
        start(new AsyncOnErrorServlet(serverLatch));
        client.setIdleTimeout(sessionTimeout);

        Session session = newClient(new Session.Listener.Adapter());
        HttpFields fields = new HttpFields();
        MetaData.Request metaData = newRequest("GET", fields);
        HeadersFrame frame = new HeadersFrame(metaData, null, true);
        FuturePromise<Stream> promise = new FuturePromise<>();
        CountDownLatch clientLatch = new CountDownLatch(1);
        session.newStream(frame, promise, new AsyncOnErrorStreamListener(clientLatch));
        Stream stream = promise.get(5, TimeUnit.SECONDS);
        stream.setIdleTimeout(streamTimeout);

        Thread.sleep(2 * Math.min(sessionTimeout, streamTimeout));

        Assert.assertTrue(serverLatch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testAsyncContextWithServerSessionIdleTimeout() throws Exception
    {
        testAsyncContextWithServerIdleTimeout(1000, 10 * 1000);
    }

    @Test
    public void testAsyncContextWithServerStreamIdleTimeout() throws Exception
    {
        testAsyncContextWithServerIdleTimeout(10 * 1000, 1000);
    }

    private void testAsyncContextWithServerIdleTimeout(long sessionTimeout, long streamTimeout) throws Exception
    {
        CountDownLatch serverLatch = new CountDownLatch(1);
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
        ServletContextHandler context = new ServletContextHandler(server, "/", true, false);
        context.addServlet(new ServletHolder(new AsyncOnErrorServlet(serverLatch)), servletPath + "/*");
        server.start();

        prepareClient();
        client.start();

        Session session = newClient(new Session.Listener.Adapter());
        HttpFields fields = new HttpFields();
        MetaData.Request metaData = newRequest("GET", fields);
        HeadersFrame frame = new HeadersFrame(metaData, null, true);
        FuturePromise<Stream> promise = new FuturePromise<>();
        CountDownLatch clientLatch = new CountDownLatch(1);
        session.newStream(frame, promise, new AsyncOnErrorStreamListener(clientLatch));
        Stream stream = promise.get(5, TimeUnit.SECONDS);
        stream.setIdleTimeout(streamTimeout);

        Thread.sleep(2 * Math.min(sessionTimeout, streamTimeout));

        Assert.assertTrue(serverLatch.await(5, TimeUnit.SECONDS));
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

    private static class AsyncOnErrorStreamListener extends Stream.Listener.Adapter
    {
        private final CountDownLatch clientLatch;

        public AsyncOnErrorStreamListener(CountDownLatch clientLatch)
        {
            this.clientLatch = clientLatch;
        }

        @Override
        public void onHeaders(Stream stream, HeadersFrame frame)
        {
            MetaData.Response response = (MetaData.Response)frame.getMetaData();
            if (response.getStatus() == HttpStatus.INTERNAL_SERVER_ERROR_500)
                clientLatch.countDown();
            if (frame.isEndStream())
                clientLatch.countDown();
        }
    }
}
