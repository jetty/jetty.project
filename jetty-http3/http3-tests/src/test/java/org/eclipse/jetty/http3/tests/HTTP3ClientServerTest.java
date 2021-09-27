//
// ========================================================================
// Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http3.api.Session;
import org.eclipse.jetty.http3.api.Stream;
import org.eclipse.jetty.http3.frames.DataFrame;
import org.eclipse.jetty.http3.frames.HeadersFrame;
import org.eclipse.jetty.http3.frames.SettingsFrame;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class HTTP3ClientServerTest extends AbstractHTTP3ClientServerTest
{
    @Test
    public void testConnectTriggersSettingsFrame() throws Exception
    {
        CountDownLatch serverPrefaceLatch = new CountDownLatch(1);
        CountDownLatch serverSettingsLatch = new CountDownLatch(1);
        startServer(new Session.Server.Listener()
        {
            @Override
            public Map<Long, Long> onPreface(Session session)
            {
                serverPrefaceLatch.countDown();
                return Map.of();
            }

            @Override
            public void onSettings(Session session, SettingsFrame frame)
            {
                serverSettingsLatch.countDown();
            }
        });
        startClient();

        CountDownLatch clientPrefaceLatch = new CountDownLatch(1);
        CountDownLatch clientSettingsLatch = new CountDownLatch(1);
        Session.Client session = client.connect(new InetSocketAddress("localhost", connector.getLocalPort()), new Session.Client.Listener()
            {
                @Override
                public Map<Long, Long> onPreface(Session session)
                {
                    clientPrefaceLatch.countDown();
                    return Map.of();
                }

                @Override
                public void onSettings(Session session, SettingsFrame frame)
                {
                    clientSettingsLatch.countDown();
                }
            })
            .get(5, TimeUnit.SECONDS);
        assertNotNull(session);

        assertTrue(serverSettingsLatch.await(5, TimeUnit.SECONDS));
        assertTrue(clientSettingsLatch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testGETThenResponseWithoutContent() throws Exception
    {
        CountDownLatch serverRequestLatch = new CountDownLatch(1);
        startServer(new Session.Server.Listener()
        {
            @Override
            public Stream.Listener onRequest(Stream stream, HeadersFrame frame)
            {
                serverRequestLatch.countDown();
                // Send the response.
                stream.respond(new HeadersFrame(new MetaData.Response(HttpVersion.HTTP_3, HttpStatus.OK_200, HttpFields.EMPTY), true));
                // Not interested in request data.
                return null;
            }
        });
        startClient();

        Session.Client session = client.connect(new InetSocketAddress("localhost", connector.getLocalPort()), new Session.Client.Listener() {})
            .get(5, TimeUnit.SECONDS);

        CountDownLatch clientResponseLatch = new CountDownLatch(1);
        HttpURI uri = HttpURI.from("https://localhost:" + connector.getLocalPort() + "/");
        MetaData.Request metaData = new MetaData.Request(HttpMethod.GET.asString(), uri, HttpVersion.HTTP_3, HttpFields.EMPTY);
        HeadersFrame frame = new HeadersFrame(metaData, true);
        Stream stream = session.newRequest(frame, new Stream.Listener()
            {
                @Override
                public void onResponse(Stream stream, HeadersFrame frame)
                {
                    clientResponseLatch.countDown();
                }
            })
            .get(5, TimeUnit.SECONDS);
        assertNotNull(stream);

        assertTrue(serverRequestLatch.await(5, TimeUnit.SECONDS));
        assertTrue(clientResponseLatch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testDiscardRequestContent() throws Exception
    {
        AtomicReference<CountDownLatch> serverLatch = new AtomicReference<>(new CountDownLatch(1));
        startServer(new Session.Server.Listener()
        {
            @Override
            public Stream.Listener onRequest(Stream stream, HeadersFrame frame)
            {
                // Send the response.
                stream.respond(new HeadersFrame(new MetaData.Response(HttpVersion.HTTP_3, HttpStatus.OK_200, HttpFields.EMPTY), false));
                stream.demand();
                return new Stream.Listener()
                {
                    @Override
                    public void onDataAvailable(Stream stream)
                    {
                        // FlowControl acknowledged already.
                        Stream.Data data = stream.readData();
                        if (data == null)
                        {
                            // Call me again when you have data.
                            stream.demand();
                            return;
                        }
                        // Recycle the ByteBuffer in data.frame.
                        data.complete();
                        // Call me again immediately.
                        stream.demand();
                        if (data.isLast())
                            serverLatch.get().countDown();
                    }
                };
            }
        });
        startClient();

        Session.Client session = client.connect(new InetSocketAddress("localhost", connector.getLocalPort()), new Session.Client.Listener() {})
            .get(5, TimeUnit.SECONDS);

        AtomicReference<CountDownLatch> clientLatch = new AtomicReference<>(new CountDownLatch(1));
        HttpURI uri = HttpURI.from("https://localhost:" + connector.getLocalPort() + "/");
        MetaData.Request metaData = new MetaData.Request(HttpMethod.POST.asString(), uri, HttpVersion.HTTP_3, HttpFields.EMPTY);
        HeadersFrame frame = new HeadersFrame(metaData, false);
        Stream.Listener streamListener = new Stream.Listener()
        {
            @Override
            public void onResponse(Stream stream, HeadersFrame frame)
            {
                clientLatch.get().countDown();
            }
        };
        Stream stream1 = session.newRequest(frame, streamListener)
            .get(5, TimeUnit.SECONDS);
        stream1.data(new DataFrame(ByteBuffer.allocate(8192), true));

        assertTrue(clientLatch.get().await(5, TimeUnit.SECONDS));
        assertTrue(serverLatch.get().await(5, TimeUnit.SECONDS));

        // Send another request, but with 2 chunks of data separated by some time.
        serverLatch.set(new CountDownLatch(1));
        clientLatch.set(new CountDownLatch(1));
        Stream stream2 = session.newRequest(frame, streamListener).get(5, TimeUnit.SECONDS);
        stream2.data(new DataFrame(ByteBuffer.allocate(3 * 1024), false));
        // Wait some time before sending the second chunk.
        Thread.sleep(500);
        stream2.data(new DataFrame(ByteBuffer.allocate(5 * 1024), true));

        assertTrue(clientLatch.get().await(5, TimeUnit.SECONDS));
        assertTrue(serverLatch.get().await(5, TimeUnit.SECONDS));
    }

    @ParameterizedTest
    @ValueSource(ints = {1024, 10 * 1024, 100 * 1024, 1000 * 1024})
    public void testEchoRequestContentAsResponseContent(int length) throws Exception
    {
        startServer(new Session.Server.Listener()
        {
            @Override
            public Stream.Listener onRequest(Stream stream, HeadersFrame frame)
            {
                // Send the response headers.
                stream.respond(new HeadersFrame(new MetaData.Response(HttpVersion.HTTP_3, HttpStatus.OK_200, HttpFields.EMPTY), false));
                stream.demand();
                return new Stream.Listener()
                {
                    @Override
                    public void onDataAvailable(Stream stream)
                    {
                        // Read data.
                        Stream.Data data = stream.readData();
                        if (data == null)
                        {
                            stream.demand();
                            return;
                        }
                        // Echo it back, then demand only when the write is finished.
                        stream.data(new DataFrame(data.getByteBuffer(), data.isLast()))
                            // Always complete.
                            .whenComplete((s, x) -> data.complete())
                            // Demand only if successful.
                            .thenRun(stream::demand);
                    }
                };
            }
        });
        startClient();

        Session.Client session = client.connect(new InetSocketAddress("localhost", connector.getLocalPort()), new Session.Client.Listener() {})
            .get(5, TimeUnit.SECONDS);

        CountDownLatch clientResponseLatch = new CountDownLatch(1);
        HttpURI uri = HttpURI.from("https://localhost:" + connector.getLocalPort() + "/");
        MetaData.Request metaData = new MetaData.Request(HttpMethod.GET.asString(), uri, HttpVersion.HTTP_3, HttpFields.EMPTY);
        HeadersFrame frame = new HeadersFrame(metaData, false);
        byte[] bytesSent = new byte[length];
        new Random().nextBytes(bytesSent);
        byte[] bytesReceived = new byte[bytesSent.length];
        ByteBuffer byteBuffer = ByteBuffer.wrap(bytesReceived);
        CountDownLatch clientDataLatch = new CountDownLatch(1);
        Stream stream = session.newRequest(frame, new Stream.Listener()
            {
                @Override
                public void onResponse(Stream stream, HeadersFrame frame)
                {
                    clientResponseLatch.countDown();
                    stream.demand();
                }

                @Override
                public void onDataAvailable(Stream stream)
                {
                    // Read data.
                    Stream.Data data = stream.readData();
                    if (data != null)
                    {
                        // Consume data.
                        byteBuffer.put(data.getByteBuffer());
                        data.complete();
                        if (data.isLast())
                            clientDataLatch.countDown();
                    }
                    // Demand more data.
                    stream.demand();
                }
            })
            .get(5, TimeUnit.SECONDS);
        stream.data(new DataFrame(ByteBuffer.wrap(bytesSent), true));

        assertTrue(clientResponseLatch.await(5, TimeUnit.SECONDS));
        assertTrue(clientDataLatch.await(5, TimeUnit.SECONDS));
        assertArrayEquals(bytesSent, bytesReceived);
    }
}
