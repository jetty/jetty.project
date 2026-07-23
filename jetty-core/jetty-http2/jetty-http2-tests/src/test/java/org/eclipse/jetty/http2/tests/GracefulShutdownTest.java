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

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.client.ContentResponse;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http2.api.Session;
import org.eclipse.jetty.http2.api.Stream;
import org.eclipse.jetty.http2.api.server.ServerSessionListener;
import org.eclipse.jetty.http2.frames.DataFrame;
import org.eclipse.jetty.http2.frames.GoAwayFrame;
import org.eclipse.jetty.http2.frames.HeadersFrame;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.component.Graceful;
import org.eclipse.jetty.util.component.LifeCycle;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class GracefulShutdownTest extends AbstractTest
{
    @Override
    protected void start(ServerSessionListener listener) throws Exception
    {
        super.start(listener);
        // Don't let the default shutdown idleTimeout
        // of just 1000 ms interfere with the test.
        connector.setShutdownIdleTimeout(15000);
    }

    // Issue #13602: connector.shutdown() then connector.stop() while an HTTP/2 client is connected must
    // still complete the shutdown future rather than leave it uncompleted.
    @Test
    public void testConnectorShutdownThenStopCompletesFutureOnHTTP2() throws Exception
    {
        start(new org.eclipse.jetty.server.Handler.Abstract()
        {
            @Override
            public boolean handle(org.eclipse.jetty.server.Request request, org.eclipse.jetty.server.Response response, Callback callback)
            {
                response.setStatus(HttpStatus.OK_200);
                callback.succeeded();
                return true;
            }
        });
        connector.setShutdownIdleTimeout(15000);

        ContentResponse response = httpClient.newRequest("localhost", connector.getLocalPort())
            .method(HttpMethod.GET)
            .send();
        assertTrue(response.getStatus() == HttpStatus.OK_200 || response.getStatus() == HttpStatus.NOT_FOUND_404);

        CompletableFuture<Void> shutdownFuture = connector.shutdown();
        Thread.sleep(1000);
        connector.stop();

        // Bounded so a never-completing future fails the test rather than blocking forever.
        assertNull(shutdownFuture.get(10, TimeUnit.SECONDS));
    }

    // Issue #13602 with a stream still active during shutdown: connector.stop() must still complete the
    // shutdown future even when the connection cannot be closed before stop().
    @Test
    public void testShutdownThenStopWithActiveStreamStillCompletes() throws Exception
    {
        CountDownLatch serverGotRequest = new CountDownLatch(1);
        start(new org.eclipse.jetty.server.Handler.Abstract()
        {
            @Override
            public boolean handle(org.eclipse.jetty.server.Request request, org.eclipse.jetty.server.Response response, Callback callback)
            {
                // Never complete the callback, keeping the stream active across the shutdown.
                serverGotRequest.countDown();
                return true;
            }
        });
        connector.setShutdownIdleTimeout(15000);

        httpClient.newRequest("localhost", connector.getLocalPort()).method(HttpMethod.GET).send(result -> {});
        assertTrue(serverGotRequest.await(5, TimeUnit.SECONDS));

        CompletableFuture<Void> shutdownFuture = connector.shutdown();
        Thread.sleep(1000);
        connector.stop();

        // Bounded so a never-completing future fails the test rather than blocking forever.
        assertNull(shutdownFuture.get(10, TimeUnit.SECONDS));
    }

    @Test
    public void testGracefulShutdownWhileIdle() throws Exception
    {
        start(new ServerSessionListener()
        {
            @Override
            public Stream.Listener onNewStream(Stream stream, HeadersFrame frame)
            {
                MetaData.Response response = new MetaData.Response(HttpStatus.OK_200, null, HttpVersion.HTTP_2, HttpFields.EMPTY);
                stream.headers(new HeadersFrame(stream.getId(), response, null, true), Callback.NOOP);
                return null;
            }
        });

        CountDownLatch clientRequestLatch = new CountDownLatch(1);
        CountDownLatch clientGoAwayLatch = new CountDownLatch(2);
        CountDownLatch clientCloseLatch = new CountDownLatch(1);
        Session clientSession = newClientSession(new Session.Listener()
        {
            @Override
            public void onGoAway(Session session, GoAwayFrame frame)
            {
                // One graceful GOAWAY and one normal GOAWAY.
                clientGoAwayLatch.countDown();
            }

            @Override
            public void onClose(Session session, GoAwayFrame frame, Callback callback)
            {
                clientCloseLatch.countDown();
                callback.succeeded();
            }
        });
        MetaData.Request request = newRequest(HttpMethod.GET.asString(), HttpFields.EMPTY);
        clientSession.newStream(new HeadersFrame(request, null, true), new Stream.Listener()
        {
            @Override
            public void onHeaders(Stream stream, HeadersFrame frame)
            {
                MetaData.Response response = (MetaData.Response)frame.getMetaData();
                if (frame.isEndStream() && response.getStatus() == HttpStatus.OK_200)
                    clientRequestLatch.countDown();
            }
        });

        assertTrue(clientRequestLatch.await(5, TimeUnit.SECONDS));

        // Initiate graceful shutdown on server side.
        CompletableFuture<Void> completable = Graceful.shutdown(connector);

        assertTrue(clientGoAwayLatch.await(5, TimeUnit.SECONDS));
        assertTrue(clientCloseLatch.await(5, TimeUnit.SECONDS));
        assertNull(completable.get(5, TimeUnit.SECONDS));
    }

    @Test
    public void testGracefulShutdownWithPendingStream() throws Exception
    {
        CountDownLatch serverLatch = new CountDownLatch(1);
        start(new ServerSessionListener()
        {
            @Override
            public Stream.Listener onNewStream(Stream stream, HeadersFrame frame)
            {
                stream.demand();
                return new Stream.Listener()
                {
                    @Override
                    public void onDataAvailable(Stream stream)
                    {
                        Stream.Data data = stream.readData();
                        data.release();
                        if (data.frame().isEndStream())
                        {
                            MetaData.Response response = new MetaData.Response(HttpStatus.OK_200, null, HttpVersion.HTTP_2, HttpFields.EMPTY);
                            stream.headers(new HeadersFrame(stream.getId(), response, null, true), Callback.NOOP);
                        }
                        else
                        {
                            serverLatch.countDown();
                            stream.demand();
                        }
                    }
                };
            }
        });

        CountDownLatch clientRequestLatch = new CountDownLatch(1);
        CountDownLatch clientGoAwayLatch = new CountDownLatch(2);
        CountDownLatch clientCloseLatch = new CountDownLatch(1);
        Session clientSession = newClientSession(new Session.Listener()
        {
            @Override
            public void onGoAway(Session session, GoAwayFrame frame)
            {
                // One graceful GOAWAY and one normal GOAWAY.
                clientGoAwayLatch.countDown();
            }

            @Override
            public void onClose(Session session, GoAwayFrame frame, Callback callback)
            {
                clientCloseLatch.countDown();
                callback.succeeded();
            }
        });
        MetaData.Request request = newRequest(HttpMethod.GET.asString(), HttpFields.EMPTY);
        Stream stream = clientSession.newStream(new HeadersFrame(request, null, false), new Stream.Listener()
        {
            @Override
            public void onHeaders(Stream stream, HeadersFrame frame)
            {
                MetaData.Response response = (MetaData.Response)frame.getMetaData();
                if (frame.isEndStream() && response.getStatus() == HttpStatus.OK_200)
                    clientRequestLatch.countDown();
            }
        }).get(5, TimeUnit.SECONDS);
        stream.data(new DataFrame(stream.getId(), BufferUtil.toBuffer("hello"), false));
        // Make sure the server has seen the stream.
        assertTrue(serverLatch.await(5, TimeUnit.SECONDS));

        // Initiate graceful shutdown on server side.
        CompletableFuture<Void> completable = Graceful.shutdown(connector);

        // Make sure the completable is not completed yet, waiting for the stream.
        Thread.sleep(1000);
        assertFalse(completable.isDone());

        // Complete the stream.
        stream.data(new DataFrame(stream.getId(), BufferUtil.toBuffer("world"), true));

        assertTrue(clientGoAwayLatch.await(5, TimeUnit.SECONDS));
        assertTrue(clientCloseLatch.await(5, TimeUnit.SECONDS));
        assertNull(completable.get(5, TimeUnit.SECONDS));
    }

    @Test
    public void testGracefulShutdownAfterSessionAlreadyClosed() throws Exception
    {
        CountDownLatch serverCloseLatch = new CountDownLatch(1);
        AtomicReference<Session> serverSessionRef = new AtomicReference<>();
        start(new ServerSessionListener()
        {
            @Override
            public Stream.Listener onNewStream(Stream stream, HeadersFrame frame)
            {
                serverSessionRef.set(stream.getSession());
                MetaData.Response response = new MetaData.Response(HttpStatus.OK_200, null, HttpVersion.HTTP_2, HttpFields.EMPTY);
                stream.headers(new HeadersFrame(stream.getId(), response, null, true), Callback.NOOP);
                return null;
            }

            @Override
            public void onClose(Session session, GoAwayFrame frame, Callback callback)
            {
                serverCloseLatch.countDown();
                callback.succeeded();
            }
        });

        CountDownLatch clientRequestLatch = new CountDownLatch(1);
        Session clientSession = newClientSession(new Session.Listener() {});
        MetaData.Request request = newRequest(HttpMethod.GET.asString(), HttpFields.EMPTY);
        clientSession.newStream(new HeadersFrame(request, null, true), new Stream.Listener()
        {
            @Override
            public void onHeaders(Stream stream, HeadersFrame frame)
            {
                MetaData.Response response = (MetaData.Response)frame.getMetaData();
                if (frame.isEndStream() && response.getStatus() == HttpStatus.OK_200)
                    clientRequestLatch.countDown();
            }
        });

        assertTrue(clientRequestLatch.await(5, TimeUnit.SECONDS));

        LifeCycle.stop(clientSession);

        assertTrue(serverCloseLatch.await(5, TimeUnit.SECONDS));

        Session serverSession = serverSessionRef.get();
        assertNotNull(serverSession);

        // Simulate a race condition where session.shutdown()
        // is called after the session is closed.
        CompletableFuture<Void> completable = serverSession.shutdown();
        // Verify that it is completed.
        assertTrue(completable.isDone());
    }
}
