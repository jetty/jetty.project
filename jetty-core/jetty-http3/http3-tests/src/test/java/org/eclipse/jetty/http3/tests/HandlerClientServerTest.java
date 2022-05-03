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
import org.eclipse.jetty.server.Content;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class HandlerClientServerTest extends AbstractClientServerTest
{
    @Test
    public void testGet() throws Exception
    {
        CountDownLatch serverLatch = new CountDownLatch(1);
        start(new Handler.Processor()
        {
            @Override
            public void process(Request request, Response response, Callback callback)
            {
                serverLatch.countDown();
                callback.succeeded();
            }
        });

        Session.Client session = newSession(new Session.Client.Listener() {});

        CountDownLatch clientResponseLatch = new CountDownLatch(1);
        HeadersFrame frame = new HeadersFrame(newRequest("/"), true);
        session.newRequest(frame, new Stream.Client.Listener()
            {
                @Override
                public void onResponse(Stream.Client stream, HeadersFrame frame)
                {
                    MetaData.Response response = (MetaData.Response)frame.getMetaData();
                    assertThat(response.getStatus(), is(HttpStatus.OK_200));
                    clientResponseLatch.countDown();
                }
            })
            .get(5, TimeUnit.SECONDS);

        assertTrue(serverLatch.await(5, TimeUnit.SECONDS));
        assertTrue(clientResponseLatch.await(5, TimeUnit.SECONDS));
    }

    @Disabled
    @Test
    public void testPost() throws Exception
    {
        CountDownLatch serverLatch = new CountDownLatch(1);
        start(new Handler.Processor()
        {
            @Override
            public void process(Request request, Response response, Callback callback)
            {
                Content.copy(request, response, callback);
                serverLatch.countDown();
            }
        });

        Session.Client session = newSession(new Session.Client.Listener() {});

        List<ByteBuffer> clientReceivedBuffers = new ArrayList<>();

        CountDownLatch clientResponseLatch = new CountDownLatch(1);
        HeadersFrame frame = new HeadersFrame(newRequest(HttpMethod.POST, "/"), false);
        Stream stream = session.newRequest(frame, new Stream.Client.Listener()
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
                    Stream.Data data = stream.readData();
                    if (data == null)
                    {
                        stream.demand();
                        return;
                    }

                    ByteBuffer byteBuffer = data.getByteBuffer();
                    ByteBuffer copy = ByteBuffer.allocate(byteBuffer.remaining());
                    copy.put(byteBuffer);
                    copy.flip();
                    clientReceivedBuffers.add(copy);
                    data.complete();

                    if (data.isLast())
                    {
                        clientResponseLatch.countDown();
                        return;
                    }

                    stream.demand();
                }
            })
            .get(5, TimeUnit.SECONDS);

        byte[] bytes = new byte[1024];
        new Random().nextBytes(bytes);
        stream.data(new DataFrame(ByteBuffer.wrap(bytes, 0, bytes.length / 2), false))
            .thenCompose(s -> s.data(new DataFrame(ByteBuffer.wrap(bytes, bytes.length / 2, bytes.length / 2), true)))
            .get(555, TimeUnit.SECONDS);

        assertTrue(serverLatch.await(555, TimeUnit.SECONDS));
        assertTrue(clientResponseLatch.await(555, TimeUnit.SECONDS));

        int sum = clientReceivedBuffers.stream().mapToInt(Buffer::remaining).sum();
        assertThat(sum, is(bytes.length));

        byte[] mirroredBytes = new byte[sum];
        ByteBuffer clientBuffer = ByteBuffer.wrap(mirroredBytes);
        clientReceivedBuffers.forEach(clientBuffer::put);
        assertArrayEquals(bytes, mirroredBytes);
    }
}
