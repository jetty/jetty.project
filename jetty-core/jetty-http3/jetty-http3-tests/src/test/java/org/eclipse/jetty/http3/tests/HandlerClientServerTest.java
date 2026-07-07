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

import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http3.api.Session;
import org.eclipse.jetty.http3.api.Stream;
import org.eclipse.jetty.http3.frames.DataFrame;
import org.eclipse.jetty.http3.frames.HeadersFrame;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Blocker;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.Promise;
import org.eclipse.jetty.util.buffer.ReadableBuffer;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class HandlerClientServerTest extends AbstractClientServerTest
{
    @ParameterizedTest
    @MethodSource("transports")
    public void testGet(TransportType transportType) throws Exception
    {
        CountDownLatch serverLatch = new CountDownLatch(1);
        start(transportType, new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                serverLatch.countDown();
                callback.succeeded();
                return true;
            }
        });

        Session.Client session = newSession(new Session.Client.Listener() {});

        CountDownLatch clientResponseLatch = new CountDownLatch(1);
        HeadersFrame frame = new HeadersFrame(newRequest("/"), true);
        Blocker.<Stream>blockWithPromise(5, TimeUnit.SECONDS, p -> session.newRequest(frame, new Stream.Client.Listener()
        {
            @Override
            public void onResponse(Stream.Client stream, HeadersFrame frame)
            {
                MetaData.Response response = (MetaData.Response)frame.getMetaData();
                assertThat(response.getStatus(), is(HttpStatus.OK_200));
                clientResponseLatch.countDown();
            }
        }, p));

        assertTrue(serverLatch.await(5, TimeUnit.SECONDS));
        assertTrue(clientResponseLatch.await(5, TimeUnit.SECONDS));
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testPost(TransportType transportType) throws Exception
    {
        CountDownLatch serverLatch = new CountDownLatch(1);
        start(transportType, new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                Content.copy(request, response, callback);
                serverLatch.countDown();
                return true;
            }
        });

        Session.Client session = newSession(new Session.Client.Listener() {});

        List<ByteBuffer> clientReceivedBuffers = new ArrayList<>();

        CountDownLatch clientResponseLatch = new CountDownLatch(1);
        HeadersFrame frame = new HeadersFrame(newRequest(HttpMethod.POST, "/"), false);
        Stream stream = Blocker.blockWithPromise(5, TimeUnit.SECONDS, p -> session.newRequest(frame, new Stream.Client.Listener()
        {
            @Override
            public void onResponse(Stream.Client stream, HeadersFrame frame)
            {
                MetaData.Response response = (MetaData.Response)frame.getMetaData();
                assertThat(response.getStatus(), is(HttpStatus.OK_200));
                stream.demand();
            }

            @Override
            public void onDataAvailable(Stream.Client stream)
            {
                Content.Chunk chunk = stream.read();
                if (chunk == null)
                {
                    stream.demand();
                    return;
                }

                ByteBuffer byteBuffer = chunk.getByteBuffer();
                ByteBuffer copy = ByteBuffer.allocate(byteBuffer.remaining());
                copy.put(byteBuffer);
                copy.flip();
                clientReceivedBuffers.add(copy);
                chunk.release();

                if (chunk.isLast())
                {
                    clientResponseLatch.countDown();
                    return;
                }

                stream.demand();
            }
        }, p));

        byte[] bytes = new byte[1024];
        new Random().nextBytes(bytes);
        Blocker.<Stream>blockWithPromise(5, TimeUnit.SECONDS, p -> stream.data(new DataFrame(ReadableBuffer.wrap(bytes, 0, bytes.length / 2), false), new Promise.Invocable.NonBlocking<>()
        {
            @Override
            public void succeeded(Stream result)
            {
                result.data(new DataFrame(ReadableBuffer.wrap(bytes, bytes.length / 2, bytes.length / 2), true), p);
            }
        }));

        assertTrue(serverLatch.await(5, TimeUnit.SECONDS));
        assertTrue(clientResponseLatch.await(5, TimeUnit.SECONDS));

        int sum = clientReceivedBuffers.stream().mapToInt(Buffer::remaining).sum();
        assertThat(sum, is(bytes.length));

        byte[] mirroredBytes = new byte[sum];
        ByteBuffer clientBuffer = ByteBuffer.wrap(mirroredBytes);
        clientReceivedBuffers.forEach(clientBuffer::put);
        assertArrayEquals(bytes, mirroredBytes);
    }
}
