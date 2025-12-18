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

package org.eclipse.jetty.http3.tests;

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
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http3.api.Session;
import org.eclipse.jetty.http3.api.Stream;
import org.eclipse.jetty.http3.client.HTTP3SessionClient;
import org.eclipse.jetty.http3.frames.HeadersFrame;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.Promise;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ConcurrentRequestsTest extends AbstractClientServerTest
{
    @ParameterizedTest
    @MethodSource("transports")
    public void testConcurrentGoodRequestsWithBadRequestsWithSameConnection(TransportType transportType) throws Exception
    {
        start(transportType, new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                callback.succeeded();
                return true;
            }
        });

        Session.Client clientSession = newSession(new Session.Client.Listener() {});
        // The test will send an invalid header value on this connection.
        ((HTTP3SessionClient)clientSession).getQpackEncoder().setValidateEncoding(false);

        testConcurrentGoodRequestsWithBadRequests(clientSession, clientSession);
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testConcurrentGoodRequestsWithBadRequestsWithDifferentConnections(TransportType transportType) throws Exception
    {
        start(transportType, new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                callback.succeeded();
                return true;
            }
        });

        Session.Client goodClientSession = newSession(new Session.Client.Listener() {});
        Session.Client badClientSession = newSession(new Session.Client.Listener() {});
        // The test will send an invalid header value on this connection.
        ((HTTP3SessionClient)badClientSession).getQpackEncoder().setValidateEncoding(false);

        testConcurrentGoodRequestsWithBadRequests(goodClientSession, badClientSession);
    }

    private void testConcurrentGoodRequestsWithBadRequests(Session.Client goodSession, Session.Client badSession) throws Exception
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
        Thread.sleep(500);

        testing.set(false);
        executor.shutdown();
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));

        assertThat(failures, empty());
    }

    private boolean sendGoodRequest(Session.Client clientSession, List<Throwable> failures, long id)
    {
        AtomicBoolean result = new AtomicBoolean();
        CountDownLatch requestLatch = new CountDownLatch(1);
        MetaData.Request request = newRequest(HttpMethod.GET, "/good-" + id, HttpFields.EMPTY);
        clientSession.newRequest(new HeadersFrame(request, true), new Stream.Client.Listener()
        {
            @Override
            public void onResponse(Stream.Client stream, HeadersFrame frame)
            {
                MetaData.Response response = (MetaData.Response)frame.getMetaData();
                int status = response.getStatus();
                result.set(status == HttpStatus.OK_200 && frame.isLast());
                if (status != HttpStatus.OK_200)
                    failures.add(new HttpException.RuntimeException(HttpStatus.BAD_REQUEST_400, "expected 200, got " + status + " id=" + id));
                if (!frame.isLast())
                    stream.demand();
                else
                    requestLatch.countDown();
            }

            @Override
            public void onDataAvailable(Stream.Client stream)
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
            public void onIdleTimeout(Stream.Client stream, Throwable failure, Promise<Boolean> promise)
            {
                failures.add(failure);
                result.set(false);
                requestLatch.countDown();
                promise.succeeded(true);
            }

            @Override
            public void onFailure(Stream.Client stream, long error, Throwable failure)
            {
                failures.add(failure);
                result.set(false);
                requestLatch.countDown();
            }
        }, Promise.Invocable.noop());

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

    private boolean sendBadRequest(Session.Client clientSession, List<Throwable> failures, long id)
    {
        AtomicBoolean result = new AtomicBoolean();
        CountDownLatch requestLatch = new CountDownLatch(1);
        HttpFields headers = HttpFields.build().put("invalid", "space_at_end ");
        MetaData.Request request = newRequest(HttpMethod.GET, "/bad-" + id, headers);
        clientSession.newRequest(new HeadersFrame(request, true), new Stream.Client.Listener()
        {
            @Override
            public void onResponse(Stream.Client stream, HeadersFrame frame)
            {
                MetaData.Response response = (MetaData.Response)frame.getMetaData();
                int status = response.getStatus();
                result.set(status == HttpStatus.BAD_REQUEST_400);
                if (status != HttpStatus.BAD_REQUEST_400)
                    failures.add(new HttpException.RuntimeException(HttpStatus.BAD_REQUEST_400, "expected 400, got " + status + " id=" + id));
                if (!frame.isLast())
                    stream.demand();
                else
                    requestLatch.countDown();
            }

            @Override
            public void onDataAvailable(Stream.Client stream)
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
            public void onIdleTimeout(Stream.Client stream, Throwable failure, Promise<Boolean> promise)
            {
                failures.add(failure);
                requestLatch.countDown();
                result.set(false);
                promise.succeeded(true);
            }

            @Override
            public void onFailure(Stream.Client stream, long error, Throwable failure)
            {
                failures.add(failure);
                requestLatch.countDown();
                result.set(false);
            }
        }, Promise.Invocable.noop());

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
