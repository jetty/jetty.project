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

package org.eclipse.jetty.http2.tests;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

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
    @Test
    public void testGracefulShutdownWhileIdle() throws Exception
    {
        start(new ServerSessionListener()
        {
            @Override
            public Stream.Listener onNewStream(Stream stream, HeadersFrame frame)
            {
                MetaData.Response response = new MetaData.Response(HttpVersion.HTTP_2, HttpStatus.OK_200, HttpFields.EMPTY);
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
                            MetaData.Response response = new MetaData.Response(HttpVersion.HTTP_2, HttpStatus.OK_200, HttpFields.EMPTY);
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
                MetaData.Response response = new MetaData.Response(HttpVersion.HTTP_2, HttpStatus.OK_200, HttpFields.EMPTY);
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
