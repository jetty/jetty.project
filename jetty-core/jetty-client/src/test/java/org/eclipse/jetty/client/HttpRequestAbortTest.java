//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.client;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.client.api.Result;
import org.eclipse.jetty.client.util.ByteBufferRequestContent;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.logging.StacklessLogging;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.internal.HttpChannelState;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.IO;
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

        Request request = client.newRequest("localhost", connector.getLocalPort()).scheme(scenario.getScheme());
        ExecutionException x = assertThrows(ExecutionException.class, () ->
        {
            request.timeout(5, TimeUnit.SECONDS);
            request.abort(failure);
            request.send();
        });

        assertSame(failure, x.getCause());
        // Make sure the pool is in a sane state.
        HttpDestination destination = (HttpDestination)client.resolveDestination(request);
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

        Throwable cause = new Exception();
        AtomicBoolean aborted = new AtomicBoolean();
        CountDownLatch latch = new CountDownLatch(1);
        AtomicBoolean begin = new AtomicBoolean();

        Request request = client.newRequest("localhost", connector.getLocalPort()).scheme(scenario.getScheme());
        ExecutionException x = assertThrows(ExecutionException.class, () ->
        {
            request.listener(new Request.Listener.Adapter()
            {
                @Override
                public void onQueued(Request request)
                {
//                    aborted.set(request.abort(cause)); // TODO
                    latch.countDown();
                }

                @Override
                public void onBegin(Request request)
                {
                    begin.set(true);
                }
            }).timeout(5, TimeUnit.SECONDS).send();
        });

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        if (aborted.get())
            assertSame(cause, x.getCause());
        assertFalse(begin.get());

        HttpDestination destination = (HttpDestination)client.resolveDestination(request);
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

        Request request = client.newRequest("localhost", connector.getLocalPort()).scheme(scenario.getScheme());
        ExecutionException x = assertThrows(ExecutionException.class, () ->
        {
            request.listener(new Request.Listener.Adapter()
            {
                @Override
                public void onBegin(Request request)
                {
//                    aborted.set(request.abort(cause)); // TODO
                    latch.countDown();
                }

                @Override
                public void onCommit(Request request)
                {
                    committed.countDown();
                }
            }).timeout(5, TimeUnit.SECONDS).send();
        });
        assertTrue(latch.await(5, TimeUnit.SECONDS));
        if (aborted.get())
            assertSame(cause, x.getCause());
        assertFalse(committed.await(1, TimeUnit.SECONDS));

        HttpDestination destination = (HttpDestination)client.resolveDestination(request);
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

        Request request = client.newRequest("localhost", connector.getLocalPort()).scheme(scenario.getScheme());
        ExecutionException x = assertThrows(ExecutionException.class, () ->
        {
            request.listener(new Request.Listener.Adapter()
            {
                @Override
                public void onHeaders(Request request)
                {
//                    aborted.set(request.abort(cause)); // TODO
                    latch.countDown();
                }

                @Override
                public void onCommit(Request request)
                {
                    committed.countDown();
                }
            }).timeout(5, TimeUnit.SECONDS).send();
        });
        assertTrue(latch.await(5, TimeUnit.SECONDS));
        if (aborted.get())
            assertSame(cause, x.getCause());
        assertFalse(committed.await(1, TimeUnit.SECONDS));

        HttpDestination destination = (HttpDestination)client.resolveDestination(request);
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

        Throwable cause = new Exception();
        AtomicBoolean aborted = new AtomicBoolean();
        CountDownLatch latch = new CountDownLatch(1);

        Request request = client.newRequest("localhost", connector.getLocalPort()).scheme(scenario.getScheme());
        ExecutionException x = assertThrows(ExecutionException.class, () ->
        {
            request.onRequestCommit(r ->
            {
//                aborted.set(r.abort(cause)); // TODO
                latch.countDown();
            }).timeout(5, TimeUnit.SECONDS).send();
        });
        assertTrue(latch.await(5, TimeUnit.SECONDS));
        if (aborted.get())
            assertSame(cause, x.getCause());

        HttpDestination destination = (HttpDestination)client.resolveDestination(request);
        DuplexConnectionPool connectionPool = (DuplexConnectionPool)destination.getConnectionPool();
        assertEquals(0, connectionPool.getConnectionCount());
        assertEquals(0, connectionPool.getActiveConnections().size());
        assertEquals(0, connectionPool.getIdleConnections().size());
    }

    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void testAbortOnCommitWithContent(Scenario scenario) throws Exception
    {
        start(scenario, new EmptyServerHandler()
        {
            @Override
            protected void service(org.eclipse.jetty.server.Request request, Response response) throws IOException
            {
                InputStream inputStream = org.eclipse.jetty.server.Request.asInputStream(request);
                OutputStream outputStream = Content.Sink.asOutputStream(response);
                IO.copy(inputStream, outputStream);
            }
        });

        Throwable cause = new Exception();
        AtomicBoolean aborted = new AtomicBoolean();
        CountDownLatch latch = new CountDownLatch(1);

        Request request = client.newRequest("localhost", connector.getLocalPort()).scheme(scenario.getScheme());
        ExecutionException x = assertThrows(ExecutionException.class, () ->
        {
            request.onRequestCommit(r ->
            {
//                aborted.set(r.abort(cause)); // TODO
                latch.countDown();
            }).body(new ByteBufferRequestContent(ByteBuffer.wrap(new byte[]{0}), ByteBuffer.wrap(new byte[]{1}))
            {
                @Override
                public long getLength()
                {
                    return -1;
                }
            }).timeout(5, TimeUnit.SECONDS).send();
        });
        assertTrue(latch.await(5, TimeUnit.SECONDS));
        if (aborted.get())
            assertSame(cause, x.getCause());

        HttpDestination destination = (HttpDestination)client.resolveDestination(request);
        DuplexConnectionPool connectionPool = (DuplexConnectionPool)destination.getConnectionPool();
        assertEquals(0, connectionPool.getConnectionCount());
        assertEquals(0, connectionPool.getActiveConnections().size());
        assertEquals(0, connectionPool.getIdleConnections().size());
    }

    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void testAbortOnContent(Scenario scenario) throws Exception
    {
        try (StacklessLogging ignore = new StacklessLogging(HttpChannelState.class))
        {
            CountDownLatch serverLatch = new CountDownLatch(1);
            start(scenario, new EmptyServerHandler()
            {
                @Override
                protected void service(org.eclipse.jetty.server.Request request, Response response) throws Exception
                {
                    try
                    {
                        InputStream inputStream = org.eclipse.jetty.server.Request.asInputStream(request);
                        OutputStream outputStream = Content.Sink.asOutputStream(response);
                        IO.copy(inputStream, outputStream);
                    }
                    finally
                    {
                        serverLatch.countDown();
                    }
                }
            });

            Throwable cause = new Exception();
            AtomicBoolean aborted = new AtomicBoolean();
            CountDownLatch latch = new CountDownLatch(1);

            Request request = client.newRequest("localhost", connector.getLocalPort()).scheme(scenario.getScheme());
            ExecutionException x = assertThrows(ExecutionException.class, () ->
            {
                request.onRequestContent((r, c) ->
                {
//                    aborted.set(r.abort(cause)); // TODO
                    latch.countDown();
                }).body(new ByteBufferRequestContent(ByteBuffer.wrap(new byte[]{0}), ByteBuffer.wrap(new byte[]{1}))
                {
                    @Override
                    public long getLength()
                    {
                        return -1;
                    }
                }).timeout(5, TimeUnit.SECONDS).send();
            });
            assertTrue(latch.await(5, TimeUnit.SECONDS));
            if (aborted.get())
                assertSame(cause, x.getCause());

            assertTrue(serverLatch.await(5, TimeUnit.SECONDS));

            HttpDestination destination = (HttpDestination)client.resolveDestination(request);
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
        long delay = 1000;
        start(scenario, new EmptyServerHandler()
        {
            @Override
            protected void service(org.eclipse.jetty.server.Request request, Response response) throws Exception
            {
                TimeUnit.MILLISECONDS.sleep(2 * delay);
            }
        });

        Request request = client.newRequest("localhost", connector.getLocalPort())
                .timeout(3 * delay, TimeUnit.MILLISECONDS)
                .scheme(scenario.getScheme());

        Thread thread = Thread.currentThread();
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

        assertThrows(InterruptedException.class, request::send);
    }

    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void testAbortLongPoll(Scenario scenario) throws Exception
    {
        final long delay = 1000;
        start(scenario, new EmptyServerHandler()
        {
            @Override
            protected void service(org.eclipse.jetty.server.Request request, Response response) throws Exception
            {
                TimeUnit.MILLISECONDS.sleep(2 * delay);
            }
        });

        Request request = client.newRequest("localhost", connector.getLocalPort())
                .timeout(3 * delay, TimeUnit.MILLISECONDS)
                .scheme(scenario.getScheme());

        Throwable cause = new Exception();
        AtomicBoolean aborted = new AtomicBoolean();
        CountDownLatch latch = new CountDownLatch(1);
        new Thread(() ->
        {
            try
            {
                TimeUnit.MILLISECONDS.sleep(delay);
//                aborted.set(request.abort(cause)); // TODO
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

        HttpDestination destination = (HttpDestination)client.resolveDestination(request);
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
        start(scenario, new EmptyServerHandler()
        {
            @Override
            protected void service(org.eclipse.jetty.server.Request request, Response response) throws Throwable
            {
                TimeUnit.MILLISECONDS.sleep(2 * delay);
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
        start(scenario, new Handler.Processor()
        {
            @Override
            public void process(org.eclipse.jetty.server.Request request, Response response, Callback callback)
            {
                if ("/done".equals(request.getPathInContext()))
                    callback.succeeded();
                else
                    Response.sendRedirect(request, response, callback, "/done");
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
                    //aborted.set(result.getRequest().abort(cause)); // TODO
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
