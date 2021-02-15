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

package org.eclipse.jetty.client;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import javax.servlet.DispatcherType;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.client.api.Result;
import org.eclipse.jetty.client.http.HttpDestinationOverHTTP;
import org.eclipse.jetty.client.util.ByteBufferContentProvider;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.log.StacklessLogging;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ArgumentsSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class HttpRequestAbortTest extends AbstractHttpClientServerTest
{
    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void testAbortBeforeQueued(Scenario scenario) throws Exception
    {
        start(scenario, new EmptyServerHandler());

        Exception failure = new Exception("oops");

        ExecutionException x = assertThrows(ExecutionException.class, () ->
        {
            Request request = client.newRequest("localhost", connector.getLocalPort())
                .scheme(scenario.getScheme())
                .timeout(5, TimeUnit.SECONDS);
            request.abort(failure);
            request.send();
        });

        assertSame(failure, x.getCause());
        // Make sure the pool is in a sane state.
        HttpDestination destination = (HttpDestination)client.getDestination(scenario.getScheme(), "localhost", connector.getLocalPort());
        DuplexConnectionPool connectionPool = (DuplexConnectionPool)destination.getConnectionPool();
        assertEquals(1, connectionPool.getConnectionCount());
        assertEquals(0, connectionPool.getActiveConnections().size());
        assertEquals(1, connectionPool.getIdleConnections().size());
    }

    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void testAbortOnQueued(Scenario scenario) throws Exception
    {
        start(scenario, new EmptyServerHandler());

        final Throwable cause = new Exception();
        final AtomicBoolean aborted = new AtomicBoolean();
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicBoolean begin = new AtomicBoolean();

        ExecutionException x = assertThrows(ExecutionException.class, () ->
        {
            client.newRequest("localhost", connector.getLocalPort())
                .scheme(scenario.getScheme())
                .listener(new Request.Listener.Adapter()
                {
                    @Override
                    public void onQueued(Request request)
                    {
                        aborted.set(request.abort(cause));
                        latch.countDown();
                    }

                    @Override
                    public void onBegin(Request request)
                    {
                        begin.set(true);
                    }
                })
                .timeout(5, TimeUnit.SECONDS)
                .send();
        });

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        if (aborted.get())
            assertSame(cause, x.getCause());
        assertFalse(begin.get());

        HttpDestinationOverHTTP destination = (HttpDestinationOverHTTP)client.getDestination(scenario.getScheme(), "localhost", connector.getLocalPort());
        DuplexConnectionPool connectionPool = (DuplexConnectionPool)destination.getConnectionPool();
        assertEquals(0, connectionPool.getConnectionCount());
        assertEquals(0, connectionPool.getActiveConnections().size());
        assertEquals(0, connectionPool.getIdleConnections().size());
    }

    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void testAbortOnBegin(Scenario scenario) throws Exception
    {
        start(scenario, new EmptyServerHandler());

        final Throwable cause = new Exception();
        final AtomicBoolean aborted = new AtomicBoolean();
        final CountDownLatch latch = new CountDownLatch(1);
        final CountDownLatch committed = new CountDownLatch(1);

        ExecutionException x = assertThrows(ExecutionException.class, () ->
        {
            client.newRequest("localhost", connector.getLocalPort())
                .scheme(scenario.getScheme())
                .listener(new Request.Listener.Adapter()
                {
                    @Override
                    public void onBegin(Request request)
                    {
                        aborted.set(request.abort(cause));
                        latch.countDown();
                    }

                    @Override
                    public void onCommit(Request request)
                    {
                        committed.countDown();
                    }
                })
                .timeout(5, TimeUnit.SECONDS)
                .send();
        });
        assertTrue(latch.await(5, TimeUnit.SECONDS));
        if (aborted.get())
            assertSame(cause, x.getCause());
        assertFalse(committed.await(1, TimeUnit.SECONDS));

        HttpDestinationOverHTTP destination = (HttpDestinationOverHTTP)client.getDestination(scenario.getScheme(), "localhost", connector.getLocalPort());
        DuplexConnectionPool connectionPool = (DuplexConnectionPool)destination.getConnectionPool();
        assertEquals(0, connectionPool.getConnectionCount());
        assertEquals(0, connectionPool.getActiveConnections().size());
        assertEquals(0, connectionPool.getIdleConnections().size());
    }

    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void testAbortOnHeaders(Scenario scenario) throws Exception
    {
        start(scenario, new EmptyServerHandler());

        final Throwable cause = new Exception();
        final AtomicBoolean aborted = new AtomicBoolean();
        final CountDownLatch latch = new CountDownLatch(1);
        final CountDownLatch committed = new CountDownLatch(1);

        ExecutionException x = assertThrows(ExecutionException.class, () ->
        {
            client.newRequest("localhost", connector.getLocalPort())
                .scheme(scenario.getScheme())
                .listener(new Request.Listener.Adapter()
                {
                    @Override
                    public void onHeaders(Request request)
                    {
                        aborted.set(request.abort(cause));
                        latch.countDown();
                    }

                    @Override
                    public void onCommit(Request request)
                    {
                        committed.countDown();
                    }
                })
                .timeout(5, TimeUnit.SECONDS)
                .send();
        });
        assertTrue(latch.await(5, TimeUnit.SECONDS));
        if (aborted.get())
            assertSame(cause, x.getCause());
        assertFalse(committed.await(1, TimeUnit.SECONDS));

        HttpDestinationOverHTTP destination = (HttpDestinationOverHTTP)client.getDestination(scenario.getScheme(), "localhost", connector.getLocalPort());
        DuplexConnectionPool connectionPool = (DuplexConnectionPool)destination.getConnectionPool();
        assertEquals(0, connectionPool.getConnectionCount());
        assertEquals(0, connectionPool.getActiveConnections().size());
        assertEquals(0, connectionPool.getIdleConnections().size());
    }

    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void testAbortOnCommit(Scenario scenario) throws Exception
    {
        start(scenario, new EmptyServerHandler());

        // Test can behave in 2 ways:
        // A) the request is failed before the response arrived
        // B) the request is failed after the response arrived

        final Throwable cause = new Exception();
        final AtomicBoolean aborted = new AtomicBoolean();
        final CountDownLatch latch = new CountDownLatch(1);
        ExecutionException x = assertThrows(ExecutionException.class, () ->
        {
            client.newRequest("localhost", connector.getLocalPort())
                .scheme(scenario.getScheme())
                .onRequestCommit(request ->
                {
                    aborted.set(request.abort(cause));
                    latch.countDown();
                })
                .timeout(5, TimeUnit.SECONDS)
                .send();
        });
        assertTrue(latch.await(5, TimeUnit.SECONDS));
        if (aborted.get())
            assertSame(cause, x.getCause());

        HttpDestinationOverHTTP destination = (HttpDestinationOverHTTP)client.getDestination(scenario.getScheme(), "localhost", connector.getLocalPort());
        DuplexConnectionPool connectionPool = (DuplexConnectionPool)destination.getConnectionPool();
        assertEquals(0, connectionPool.getConnectionCount());
        assertEquals(0, connectionPool.getActiveConnections().size());
        assertEquals(0, connectionPool.getIdleConnections().size());
    }

    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void testAbortOnCommitWithContent(Scenario scenario) throws Exception
    {
        final AtomicReference<IOException> failure = new AtomicReference<>();
        start(scenario, new AbstractHandler()
        {
            @Override
            public void handle(String target, org.eclipse.jetty.server.Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                try
                {
                    baseRequest.setHandled(true);
                    if (request.getDispatcherType() != DispatcherType.ERROR)
                        IO.copy(request.getInputStream(), response.getOutputStream());
                }
                catch (IOException x)
                {
                    failure.set(x);
                    throw x;
                }
            }
        });

        final Throwable cause = new Exception();
        final AtomicBoolean aborted = new AtomicBoolean();
        final CountDownLatch latch = new CountDownLatch(1);

        ExecutionException x = assertThrows(ExecutionException.class, () ->
        {
            client.newRequest("localhost", connector.getLocalPort())
                .scheme(scenario.getScheme())
                .onRequestCommit(request ->
                {
                    aborted.set(request.abort(cause));
                    latch.countDown();
                })
                .content(new ByteBufferContentProvider(ByteBuffer.wrap(new byte[]{0}), ByteBuffer.wrap(new byte[]{1}))
                {
                    @Override
                    public long getLength()
                    {
                        return -1;
                    }
                })
                .timeout(5, TimeUnit.SECONDS)
                .send();
        });
        assertTrue(latch.await(5, TimeUnit.SECONDS));
        if (aborted.get())
            assertSame(cause, x.getCause());

        HttpDestinationOverHTTP destination = (HttpDestinationOverHTTP)client.getDestination(scenario.getScheme(), "localhost", connector.getLocalPort());
        DuplexConnectionPool connectionPool = (DuplexConnectionPool)destination.getConnectionPool();
        assertEquals(0, connectionPool.getConnectionCount());
        assertEquals(0, connectionPool.getActiveConnections().size());
        assertEquals(0, connectionPool.getIdleConnections().size());
    }

    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void testAbortOnContent(Scenario scenario) throws Exception
    {
        try (StacklessLogging ignore = new StacklessLogging(org.eclipse.jetty.server.HttpChannel.class))
        {
            CountDownLatch serverLatch = new CountDownLatch(1);
            start(scenario, new EmptyServerHandler()
            {
                @Override
                protected void service(String target, org.eclipse.jetty.server.Request jettyRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
                {
                    try
                    {
                        if (request.getDispatcherType() != DispatcherType.ERROR)
                            IO.copy(request.getInputStream(), response.getOutputStream());
                    }
                    finally
                    {
                        serverLatch.countDown();
                    }
                }
            });

            final Throwable cause = new Exception();
            final AtomicBoolean aborted = new AtomicBoolean();
            final CountDownLatch latch = new CountDownLatch(1);
            ExecutionException x = assertThrows(ExecutionException.class, () ->
            {
                client.newRequest("localhost", connector.getLocalPort())
                    .scheme(scenario.getScheme())
                    .onRequestContent((request, content) ->
                    {
                        aborted.set(request.abort(cause));
                        latch.countDown();
                    })
                    .content(new ByteBufferContentProvider(ByteBuffer.wrap(new byte[]{0}), ByteBuffer.wrap(new byte[]{1}))
                    {
                        @Override
                        public long getLength()
                        {
                            return -1;
                        }
                    })
                    .timeout(5, TimeUnit.SECONDS)
                    .send();
            });
            assertTrue(latch.await(5, TimeUnit.SECONDS));
            if (aborted.get())
                assertSame(cause, x.getCause());

            assertTrue(serverLatch.await(5, TimeUnit.SECONDS));

            HttpDestinationOverHTTP destination = (HttpDestinationOverHTTP)client.getDestination(scenario.getScheme(), "localhost", connector.getLocalPort());
            DuplexConnectionPool connectionPool = (DuplexConnectionPool)destination.getConnectionPool();
            assertEquals(0, connectionPool.getConnectionCount());
            assertEquals(0, connectionPool.getActiveConnections().size());
            assertEquals(0, connectionPool.getIdleConnections().size());
        }
    }

    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void testInterrupt(Scenario scenario) throws Exception
    {
        final long delay = 1000;
        start(scenario, new AbstractHandler()
        {
            @Override
            public void handle(String target, org.eclipse.jetty.server.Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                try
                {
                    baseRequest.setHandled(true);
                    if (request.getDispatcherType() != DispatcherType.ERROR)
                        TimeUnit.MILLISECONDS.sleep(2 * delay);
                }
                catch (InterruptedException x)
                {
                    throw new ServletException(x);
                }
            }
        });

        Request request = client.newRequest("localhost", connector.getLocalPort())
            .timeout(3 * delay, TimeUnit.MILLISECONDS)
            .scheme(scenario.getScheme());

        final Thread thread = Thread.currentThread();
        new Thread(() ->
        {
            try
            {
                TimeUnit.MILLISECONDS.sleep(delay);
                thread.interrupt();
            }
            catch (InterruptedException x)
            {
                throw new RuntimeException(x);
            }
        }).start();

        assertThrows(InterruptedException.class, () -> request.send());
    }

    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void testAbortLongPoll(Scenario scenario) throws Exception
    {
        final long delay = 1000;
        start(scenario, new AbstractHandler()
        {
            @Override
            public void handle(String target, org.eclipse.jetty.server.Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                try
                {
                    baseRequest.setHandled(true);
                    if (request.getDispatcherType() != DispatcherType.ERROR)
                        TimeUnit.MILLISECONDS.sleep(2 * delay);
                }
                catch (InterruptedException x)
                {
                    throw new ServletException(x);
                }
            }
        });

        final Request request = client.newRequest("localhost", connector.getLocalPort())
            .timeout(3 * delay, TimeUnit.MILLISECONDS)
            .scheme(scenario.getScheme());

        final Throwable cause = new Exception();
        final AtomicBoolean aborted = new AtomicBoolean();
        final CountDownLatch latch = new CountDownLatch(1);
        new Thread(() ->
        {
            try
            {
                TimeUnit.MILLISECONDS.sleep(delay);
                aborted.set(request.abort(cause));
                latch.countDown();
            }
            catch (InterruptedException x)
            {
                throw new RuntimeException(x);
            }
        }).start();

        try
        {
            request.send();
        }
        catch (ExecutionException x)
        {
            assertTrue(latch.await(5, TimeUnit.SECONDS));
            if (aborted.get())
                assertSame(cause, x.getCause());
        }

        HttpDestinationOverHTTP destination = (HttpDestinationOverHTTP)client.getDestination(scenario.getScheme(), "localhost", connector.getLocalPort());
        DuplexConnectionPool connectionPool = (DuplexConnectionPool)destination.getConnectionPool();
        assertEquals(0, connectionPool.getConnectionCount());
        assertEquals(0, connectionPool.getActiveConnections().size());
        assertEquals(0, connectionPool.getIdleConnections().size());
    }

    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void testAbortLongPollAsync(Scenario scenario) throws Exception
    {
        final long delay = 1000;
        start(scenario, new AbstractHandler()
        {
            @Override
            public void handle(String target, org.eclipse.jetty.server.Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                try
                {
                    baseRequest.setHandled(true);
                    if (request.getDispatcherType() != DispatcherType.ERROR)
                        TimeUnit.MILLISECONDS.sleep(2 * delay);
                }
                catch (InterruptedException x)
                {
                    throw new ServletException(x);
                }
            }
        });

        final Throwable cause = new Exception();
        final CountDownLatch latch = new CountDownLatch(1);
        Request request = client.newRequest("localhost", connector.getLocalPort())
            .scheme(scenario.getScheme())
            .timeout(3 * delay, TimeUnit.MILLISECONDS);
        request.send(result ->
        {
            assertTrue(result.isFailed());
            assertSame(cause, result.getFailure());
            latch.countDown();
        });

        TimeUnit.MILLISECONDS.sleep(delay);

        request.abort(cause);

        assertTrue(latch.await(5, TimeUnit.SECONDS));
    }

    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void testAbortConversation(Scenario scenario) throws Exception
    {
        start(scenario, new AbstractHandler()
        {
            @Override
            public void handle(String target, org.eclipse.jetty.server.Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                baseRequest.setHandled(true);
                if (!"/done".equals(request.getRequestURI()))
                    response.sendRedirect("/done");
            }
        });

        // The test may fail to abort the request in this way:
        // T1 aborts the request, which aborts the sender, which shuts down the output;
        // server reads -1 and closes; T2 reads -1 and the receiver fails the response with an EOFException;
        // T1 tries to abort the receiver, but it's already failed.

        final Throwable cause = new Exception();
        final AtomicBoolean aborted = new AtomicBoolean();
        final CountDownLatch latch = new CountDownLatch(1);
        client.getProtocolHandlers().clear();
        client.getProtocolHandlers().put(new RedirectProtocolHandler(client)
        {
            @Override
            public void onComplete(Result result)
            {
                // Abort the request after the 3xx response but before issuing the next request
                if (!result.isFailed())
                {
                    aborted.set(result.getRequest().abort(cause));
                    latch.countDown();
                }
                super.onComplete(result);
            }
        });

        ExecutionException e = assertThrows(ExecutionException.class, () ->
        {
            client.newRequest("localhost", connector.getLocalPort())
                .scheme(scenario.getScheme())
                .path("/redirect")
                .timeout(5, TimeUnit.SECONDS)
                .send();
        });
        assertTrue(latch.await(5, TimeUnit.SECONDS));
        if (aborted.get())
            assertSame(cause, e.getCause());
    }
}
