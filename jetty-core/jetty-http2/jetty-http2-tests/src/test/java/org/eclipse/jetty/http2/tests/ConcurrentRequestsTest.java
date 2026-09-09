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

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import org.eclipse.jetty.http.HttpException;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http2.HTTP2Session;
import org.eclipse.jetty.http2.api.Session;
import org.eclipse.jetty.http2.api.Stream;
import org.eclipse.jetty.http2.frames.HeadersFrame;
import org.eclipse.jetty.http2.frames.ResetFrame;
import org.eclipse.jetty.http2.server.AbstractHTTP2ServerConnectionFactory;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.io.RateControl;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.Promise;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ConcurrentRequestsTest extends AbstractTest
{
    @Test
    public void testConcurrentGoodRequestsWithBadRequestsWithSameConnection() throws Exception
    {
        start(new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                callback.succeeded();
                return true;
            }
        });
        // Disable rate control for this test.
        connector.getConnectionFactory(AbstractHTTP2ServerConnectionFactory.class).setRateControlFactory(new RateControl.Factory() {});

        Session clientSession = newClientSession(new Session.Listener() {});
        // The test will send an invalid header value on this connection.
        ((HTTP2Session)clientSession).getGenerator().getHpackEncoder().setValidateEncoding(false);

        testConcurrentGoodRequestsWithBadRequests(clientSession, clientSession);
    }

    @Test
    public void testConcurrentGoodRequestsWithBadRequestsWithDifferentConnections() throws Exception
    {
        start(new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                callback.succeeded();
                return true;
            }
        });
        // Disable rate control for this test.
        connector.getConnectionFactory(AbstractHTTP2ServerConnectionFactory.class).setRateControlFactory(new RateControl.Factory() {});

        Session goodClientSession = newClientSession(new Session.Listener() {});
        Session badClientSession = newClientSession(new Session.Listener() {});
        // The test will send an invalid header value on this connection.
        ((HTTP2Session)badClientSession).getGenerator().getHpackEncoder().setValidateEncoding(false);

        testConcurrentGoodRequestsWithBadRequests(goodClientSession, badClientSession);
    }

    private void testConcurrentGoodRequestsWithBadRequests(Session goodSession, Session badSession) throws Exception
    {
        ExecutorService executor = Executors.newCachedThreadPool();

        AtomicBoolean testing = new AtomicBoolean(true);
        List<Throwable> failures = new CopyOnWriteArrayList<>();

        // Start a thread to send good requests.
        executor.execute(() ->
        {
            long count = 0;
            while (testing.get())
            {
                if (!sendGoodRequest(goodSession, failures, count++))
                    break;
            }
        });

        // Start a thread to send bad requests.
        executor.execute(() ->
        {
            long count = 0;
            while (testing.get())
            {
                if (!sendBadRequest(badSession, failures, count++))
                    break;
            }
        });

        // Let the test run for a while.
        Thread.sleep(2000);

        testing.set(false);
        executor.shutdown();
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));

        assertThat(failures, empty());
    }

    private boolean sendGoodRequest(Session clientSession, List<Throwable> failures, long id)
    {
        AtomicBoolean result = new AtomicBoolean();
        CountDownLatch requestLatch = new CountDownLatch(1);
        MetaData.Request request = newRequest("GET", "/good-" + id, HttpFields.EMPTY);
        clientSession.newStream(new HeadersFrame(request, null, true), new Stream.Listener()
        {
            @Override
            public void onHeaders(Stream stream, HeadersFrame frame)
            {
                MetaData.Response response = (MetaData.Response)frame.getMetaData();
                int status = response.getStatus();
                result.set(status == HttpStatus.OK_200 && frame.isEndStream());
                if (status != HttpStatus.OK_200)
                    failures.add(new HttpException.RuntimeException(HttpStatus.BAD_REQUEST_400, "expected 200, got " + status + " id=" + id));
                if (!frame.isEndStream())
                    stream.demand();
                else
                    requestLatch.countDown();
            }

            @Override
            public void onDataAvailable(Stream stream)
            {
                while (true)
                {
                    Content.Chunk chunk = stream.read();
                    if (chunk == null)
                    {
                        stream.demand();
                        return;
                    }
                    chunk.release();
                    if (chunk.isLast())
                    {
                        requestLatch.countDown();
                        return;
                    }
                }
            }

            @Override
            public void onReset(Stream stream, ResetFrame frame, Callback callback)
            {
                failures.add(new RuntimeException(frame.toString()));
                result.set(false);
                requestLatch.countDown();
            }

            @Override
            public void onIdleTimeout(Stream stream, TimeoutException x, Promise<Boolean> promise)
            {
                failures.add(x);
                result.set(false);
                requestLatch.countDown();
                promise.succeeded(true);
            }

            @Override
            public void onFailure(Stream stream, int error, String reason, Throwable failure, Callback callback)
            {
                failures.add(failure);
                result.set(false);
                requestLatch.countDown();
                callback.succeeded();
            }
        });

        try
        {
            Thread.sleep(1);
            if (requestLatch.await(5, TimeUnit.SECONDS))
                return result.get();
            failures.add(new TimeoutException("good request id=" + id));
            return false;
        }
        catch (InterruptedException x)
        {
            failures.add(x);
            return false;
        }
    }

    private boolean sendBadRequest(Session clientSession, List<Throwable> failures, long id)
    {
        AtomicBoolean result = new AtomicBoolean();
        CountDownLatch requestLatch = new CountDownLatch(1);
        HttpFields headers = HttpFields.build().put("invalid", "space_at_end ");
        MetaData.Request request = newRequest("GET", "/bad-" + id, headers);
        clientSession.newStream(new HeadersFrame(request, null, true), new Stream.Listener()
        {
            @Override
            public void onHeaders(Stream stream, HeadersFrame frame)
            {
                MetaData.Response response = (MetaData.Response)frame.getMetaData();
                int status = response.getStatus();
                result.set(status == HttpStatus.BAD_REQUEST_400);
                if (status != HttpStatus.BAD_REQUEST_400)
                    failures.add(new HttpException.RuntimeException(HttpStatus.BAD_REQUEST_400, "expected 400, got " + status + " id=" + id));
                if (!frame.isEndStream())
                    stream.demand();
                else
                    requestLatch.countDown();
            }

            @Override
            public void onDataAvailable(Stream stream)
            {
                while (true)
                {
                    Content.Chunk chunk = stream.read();
                    if (chunk == null)
                    {
                        stream.demand();
                        return;
                    }
                    chunk.release();
                    if (chunk.isLast())
                    {
                        requestLatch.countDown();
                        return;
                    }
                }
            }

            @Override
            public void onReset(Stream stream, ResetFrame frame, Callback callback)
            {
                failures.add(new RuntimeException(frame.toString()));
                requestLatch.countDown();
                result.set(false);
            }

            @Override
            public void onIdleTimeout(Stream stream, TimeoutException x, Promise<Boolean> promise)
            {
                failures.add(x);
                requestLatch.countDown();
                result.set(false);
                promise.succeeded(true);
            }

            @Override
            public void onFailure(Stream stream, int error, String reason, Throwable failure, Callback callback)
            {
                failures.add(failure);
                requestLatch.countDown();
                result.set(false);
                callback.succeeded();
            }
        });

        try
        {
            Thread.sleep(1);
            if (requestLatch.await(5, TimeUnit.SECONDS))
                return result.get();
            failures.add(new TimeoutException("bad request id=" + id));
            return false;
        }
        catch (InterruptedException x)
        {
            failures.add(x);
            return false;
        }
    }
}
