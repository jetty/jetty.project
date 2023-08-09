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

package org.eclipse.jetty.test.client.transport;

import java.nio.ByteBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.client.AsyncRequestContent;
import org.eclipse.jetty.client.BufferingResponseListener;
import org.eclipse.jetty.client.Connection;
import org.eclipse.jetty.client.Result;
import org.eclipse.jetty.client.transport.internal.HttpConnectionOverHTTP;
import org.eclipse.jetty.fcgi.client.transport.internal.HttpConnectionOverFCGI;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpHeaderValue;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

public class AsyncHandlerTest extends AbstractTest
{
    @ParameterizedTest
    @MethodSource("transports")
    public void testSyncReadThenEarlyEOFThenImplicitResponse(Transport transport) throws Exception
    {
        testReadEarlyEOFThenResponse(transport, true, false);
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testAsyncReadThenEarlyEOFThenImplicitResponse(Transport transport) throws Exception
    {
        testReadEarlyEOFThenResponse(transport, false, false);
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testSyncReadEarlyEOFThenExplicitResponse(Transport transport) throws Exception
    {
        testReadEarlyEOFThenResponse(transport, true, true);
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testAsyncReadEarlyEOFThenExplicitResponse(Transport transport) throws Exception
    {
        testReadEarlyEOFThenResponse(transport, false, true);
    }

    private void testReadEarlyEOFThenResponse(Transport transport, boolean syncRead, boolean explicitResponse) throws Exception
    {
        // For now FCGI is broken, waiting for #10273.
        assumeFalse(transport == Transport.FCGI);
        assumeFalse(transport.isSecure());
        assumeFalse(transport.isMultiplexed());

        CountDownLatch readLatch = new CountDownLatch(1);
        start(transport, new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                Runnable handler = new Runnable()
                {
                    @Override
                    public void run()
                    {
                        while (true)
                        {
                            Content.Chunk chunk = request.read();
                            if (chunk == null)
                            {
                                request.demand(this);
                                return;
                            }
                            // Eventually, expect early EOF failure.
                            if (Content.Chunk.isFailure(chunk))
                            {
                                // Write the response.
                                response.setStatus(HttpStatus.NO_CONTENT_204);
                                if (explicitResponse)
                                    response.write(true, null, callback);
                                else
                                    callback.succeeded();
                                return;
                            }
                            chunk.release();
                            readLatch.countDown();
                        }
                    }
                };
                if (syncRead)
                    handler.run();
                else
                    new Thread(handler).start();
                return true;
            }
        });

        ByteBuffer content = ByteBuffer.allocate(16);
        AsyncRequestContent body = new AsyncRequestContent(content);
        CountDownLatch responseLatch = new CountDownLatch(1);
        var request = client.newRequest(newURI(transport))
            .method(HttpMethod.POST)
            .body(body)
            .onResponseSuccess(r -> responseLatch.countDown())
            .onResponseFailure((r, x) -> responseLatch.countDown())
            .timeout(5, TimeUnit.SECONDS);
        Connection connection = client.resolveDestination(request).newConnection().get(5, TimeUnit.SECONDS);
        CountDownLatch completeLatch = new CountDownLatch(1);
        connection.send(request, new BufferingResponseListener()
        {
            @Override
            public void onComplete(Result result)
            {
                assertEquals(HttpStatus.NO_CONTENT_204, result.getResponse().getStatus());
                completeLatch.countDown();
            }
        });

        assertTrue(readLatch.await(5, TimeUnit.SECONDS));

        EndPoint endPoint = switch (transport)
        {
            case HTTP, UNIX_DOMAIN -> ((HttpConnectionOverHTTP)connection).getEndPoint();
            case FCGI -> ((HttpConnectionOverFCGI)connection).getEndPoint();
            default -> fail("unexpected transport: " + transport);
        };
        endPoint.shutdownOutput();

        assertTrue(responseLatch.await(5, TimeUnit.SECONDS));
        body.close();

        assertTrue(completeLatch.await(5, TimeUnit.SECONDS));
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testResponseThenShutdownThenSyncRead(Transport transport) throws Exception
    {
        testResponseThenShutdownThenRead(transport, true);
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testResponseThenShutdownThenAsyncRead(Transport transport) throws Exception
    {
        testResponseThenShutdownThenRead(transport, false);
    }

    private void testResponseThenShutdownThenRead(Transport transport, boolean syncRead) throws Exception
    {
        assumeFalse(transport.isMultiplexed());

        CountDownLatch writeLatch = new CountDownLatch(1);
        start(transport, new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                response.setStatus(HttpStatus.NO_CONTENT_204);
                response.getHeaders().put(HttpHeader.CONNECTION, HttpHeaderValue.CLOSE);
                response.write(true, null, Callback.from(writeLatch::countDown));

                Runnable reader = () -> Content.Source.consumeAll(request, callback);
                if (syncRead)
                    reader.run();
                else
                    new Thread(reader).start();
                return true;
            }
        });

        CountDownLatch resultLatch = new CountDownLatch(1);
        AsyncRequestContent body = new AsyncRequestContent();
        client.newRequest(newURI(transport))
            .method(HttpMethod.POST)
            .body(body)
            .timeout(5, TimeUnit.SECONDS)
            .send(result ->
            {
                assertTrue(result.isSucceeded());
                assertEquals(HttpStatus.NO_CONTENT_204, result.getResponse().getStatus());
                resultLatch.countDown();
            });

        assertTrue(writeLatch.await(5, TimeUnit.SECONDS));

        body.write(ByteBuffer.allocate(32), Callback.NOOP);
        body.close();

        assertTrue(resultLatch.await(5, TimeUnit.SECONDS));
    }
}
