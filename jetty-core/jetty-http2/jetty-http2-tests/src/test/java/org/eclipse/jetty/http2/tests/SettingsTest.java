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

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http2.Flags;
import org.eclipse.jetty.http2.HTTP2Session;
import org.eclipse.jetty.http2.api.Session;
import org.eclipse.jetty.http2.api.Stream;
import org.eclipse.jetty.http2.api.server.ServerSessionListener;
import org.eclipse.jetty.http2.frames.FrameType;
import org.eclipse.jetty.http2.frames.GoAwayFrame;
import org.eclipse.jetty.http2.frames.HeadersFrame;
import org.eclipse.jetty.http2.frames.PushPromiseFrame;
import org.eclipse.jetty.http2.frames.SettingsFrame;
import org.eclipse.jetty.http2.hpack.HpackException;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.util.Callback;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;

import static org.awaitility.Awaitility.await;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class SettingsTest extends AbstractTest
{
    @Test
    public void testSettingsNonZeroStreamId() throws Exception
    {
        AtomicReference<CountDownLatch> serverSettingsLatch = new AtomicReference<>(null);
        CountDownLatch serverFailureLatch = new CountDownLatch(1);
        CountDownLatch serverCloseLatch = new CountDownLatch(1);
        start(new ServerSessionListener()
        {
            @Override
            public void onSettings(Session session, SettingsFrame frame)
            {
                CountDownLatch latch = serverSettingsLatch.get();
                if (latch != null)
                    latch.countDown();
            }

            @Override
            public void onFailure(Session session, Throwable failure, Callback callback)
            {
                serverFailureLatch.countDown();
                callback.succeeded();
            }

            @Override
            public void onClose(Session session, GoAwayFrame frame, Callback callback)
            {
                serverCloseLatch.countDown();
                callback.succeeded();
            }
        });

        CountDownLatch clientGoAwayLatch = new CountDownLatch(1);
        CountDownLatch clientCloseLatch = new CountDownLatch(1);
        Session clientSession = newClientSession(new Session.Listener()
        {
            @Override
            public void onGoAway(Session session, GoAwayFrame frame)
            {
                clientGoAwayLatch.countDown();
            }

            @Override
            public void onClose(Session session, GoAwayFrame frame, Callback callback)
            {
                clientCloseLatch.countDown();
                callback.succeeded();
            }
        });

        // Wait until the client has finished the previous writes.
        Thread.sleep(1000);
        // Set the SETTINGS latch now, to avoid that it
        // is counted down during connection establishment.
        serverSettingsLatch.set(new CountDownLatch(1));
        // Write an invalid SETTINGS frame.
        ByteBuffer byteBuffer = ByteBuffer.allocate(17)
            .put((byte)0)
            .put((byte)0)
            .put((byte)0)
            .put((byte)FrameType.SETTINGS.getType())
            .put((byte)0)
            .putInt(1) // Non-Zero Stream ID
            .flip();
        ((HTTP2Session)clientSession).getEndPoint().write(Callback.NOOP, byteBuffer);

        assertFalse(serverSettingsLatch.get().await(1, TimeUnit.SECONDS));
        assertTrue(serverFailureLatch.await(5, TimeUnit.SECONDS));
        assertTrue(serverCloseLatch.await(5, TimeUnit.SECONDS));
        assertTrue(clientGoAwayLatch.await(5, TimeUnit.SECONDS));
        assertTrue(clientCloseLatch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testSettingsReplyWithPayload() throws Exception
    {
        AtomicReference<CountDownLatch> serverSettingsLatch = new AtomicReference<>(null);
        CountDownLatch serverFailureLatch = new CountDownLatch(1);
        CountDownLatch serverCloseLatch = new CountDownLatch(1);
        start(new ServerSessionListener()
        {
            @Override
            public void onSettings(Session session, SettingsFrame frame)
            {
                CountDownLatch latch = serverSettingsLatch.get();
                if (latch != null)
                    latch.countDown();
            }

            @Override
            public void onFailure(Session session, Throwable failure, Callback callback)
            {
                serverFailureLatch.countDown();
                callback.succeeded();
            }

            @Override
            public void onClose(Session session, GoAwayFrame frame, Callback callback)
            {
                serverCloseLatch.countDown();
                callback.succeeded();
            }
        });

        CountDownLatch clientGoAwayLatch = new CountDownLatch(1);
        CountDownLatch clientCloseLatch = new CountDownLatch(1);
        Session clientSession = newClientSession(new Session.Listener()
        {
            @Override
            public void onGoAway(Session session, GoAwayFrame frame)
            {
                clientGoAwayLatch.countDown();
            }

            @Override
            public void onClose(Session session, GoAwayFrame frame, Callback callback)
            {
                clientCloseLatch.countDown();
                callback.succeeded();
            }
        });

        // Wait until the client has finished the previous writes.
        Thread.sleep(1000);
        // Set the SETTINGS latch now, to avoid that it
        // is counted down during connection establishment.
        serverSettingsLatch.set(new CountDownLatch(1));
        // Write an invalid SETTINGS frame.
        ByteBuffer byteBuffer = ByteBuffer.allocate(17)
            .put((byte)0)
            .put((byte)0)
            .put((byte)6)
            .put((byte)FrameType.SETTINGS.getType())
            .put((byte)Flags.ACK)
            .putInt(0)
            .putShort((short)SettingsFrame.ENABLE_PUSH)
            .putInt(1)
            .flip();
        ((HTTP2Session)clientSession).getEndPoint().write(Callback.NOOP, byteBuffer);

        assertFalse(serverSettingsLatch.get().await(1, TimeUnit.SECONDS));
        assertTrue(serverFailureLatch.await(5, TimeUnit.SECONDS));
        assertTrue(serverCloseLatch.await(5, TimeUnit.SECONDS));
        assertTrue(clientGoAwayLatch.await(5, TimeUnit.SECONDS));
        assertTrue(clientCloseLatch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testInvalidEnablePush() throws Exception
    {
        CountDownLatch serverFailureLatch = new CountDownLatch(1);
        start(new ServerSessionListener()
        {
            @Override
            public void onFailure(Session session, Throwable failure, Callback callback)
            {
                serverFailureLatch.countDown();
                callback.succeeded();
            }
        });

        newClientSession(new Session.Listener()
        {
            @Override
            public Map<Integer, Integer> onPreface(Session session)
            {
                Map<Integer, Integer> settings = new HashMap<>();
                settings.put(SettingsFrame.ENABLE_PUSH, 2); // Invalid value.
                return settings;
            }
        });

        assertTrue(serverFailureLatch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testServerSendsEnablePush() throws Exception
    {
        start(new ServerSessionListener()
        {
            @Override
            public Map<Integer, Integer> onPreface(Session session)
            {
                Map<Integer, Integer> settings = new HashMap<>();
                // Servers cannot send "enable_push==1".
                settings.put(SettingsFrame.ENABLE_PUSH, 1);
                return settings;
            }
        });

        CountDownLatch clientFailureLatch = new CountDownLatch(1);
        newClientSession(new Session.Listener()
        {
            @Override
            public void onFailure(Session session, Throwable failure, Callback callback)
            {
                clientFailureLatch.countDown();
                callback.succeeded();
            }
        });

        assertTrue(clientFailureLatch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testServerCannotSendsPushPromiseWithPushDisabled() throws Exception
    {
        CountDownLatch serverPushFailureLatch = new CountDownLatch(1);
        start(new ServerSessionListener()
        {
            @Override
            public Stream.Listener onNewStream(Stream stream, HeadersFrame frame)
            {
                MetaData.Response response = new MetaData.Response(HttpStatus.OK_200, null, HttpVersion.HTTP_2, HttpFields.EMPTY);
                stream.headers(new HeadersFrame(stream.getId(), response, null, true))
                    .thenAccept(s ->
                    {
                        MetaData.Request push = newRequest("GET", "/push", HttpFields.EMPTY);
                        try
                        {
                            s.push(new PushPromiseFrame(s.getId(), push), Stream.Listener.AUTO_DISCARD);
                        }
                        catch (IllegalStateException x)
                        {
                            serverPushFailureLatch.countDown();
                        }
                    });
                return null;
            }
        });

        Session clientSession = newClientSession(new Session.Listener()
        {
            @Override
            public Map<Integer, Integer> onPreface(Session session)
            {
                Map<Integer, Integer> settings = new HashMap<>();
                // Disable push.
                settings.put(SettingsFrame.ENABLE_PUSH, 0);
                return settings;
            }
        });

        CountDownLatch clientResponseLatch = new CountDownLatch(1);
        CountDownLatch clientPushLatch = new CountDownLatch(1);
        MetaData.Request request = newRequest("GET", HttpFields.EMPTY);
        HeadersFrame frame = new HeadersFrame(request, null, true);
        clientSession.newStream(frame, new Stream.Listener()
        {
            @Override
            public void onHeaders(Stream stream, HeadersFrame frame)
            {
                clientResponseLatch.countDown();
            }

            @Override
            public Stream.Listener onPush(Stream stream, PushPromiseFrame frame)
            {
                clientPushLatch.countDown();
                return null;
            }
        });

        assertTrue(serverPushFailureLatch.await(5, TimeUnit.SECONDS));
        assertTrue(clientResponseLatch.await(5, TimeUnit.SECONDS));
        assertFalse(clientPushLatch.await(1, TimeUnit.SECONDS));
    }

    @Test
    public void testClientReceivesPushPromiseWhenPushDisabled() throws Exception
    {
        start(new ServerSessionListener()
        {
            @Override
            public Stream.Listener onNewStream(Stream stream, HeadersFrame frame)
            {
                try
                {
                    HTTP2Session session = (HTTP2Session)stream.getSession();
                    ByteBufferPool.Accumulator accumulator = new ByteBufferPool.Accumulator();
                    MetaData.Request push = newRequest("GET", "/push", HttpFields.EMPTY);
                    PushPromiseFrame pushFrame = new PushPromiseFrame(stream.getId(), 2, push);
                    session.getGenerator().control(accumulator, pushFrame);
                    session.getEndPoint().write(Callback.from(accumulator::release), accumulator.getByteBuffers().toArray(ByteBuffer[]::new));
                    return null;
                }
                catch (HpackException x)
                {
                    return null;
                }
            }
        });

        CountDownLatch clientFailureLatch = new CountDownLatch(1);
        Session clientSession = newClientSession(new Session.Listener()
        {
            @Override
            public Map<Integer, Integer> onPreface(Session session)
            {
                Map<Integer, Integer> settings = new HashMap<>();
                // Disable push.
                settings.put(SettingsFrame.ENABLE_PUSH, 0);
                return settings;
            }

            @Override
            public void onFailure(Session session, Throwable failure, Callback callback)
            {
                clientFailureLatch.countDown();
                callback.succeeded();
            }
        });

        // Wait until the server has finished the previous writes.
        Thread.sleep(1000);

        MetaData.Request request = newRequest("GET", HttpFields.EMPTY);
        HeadersFrame frame = new HeadersFrame(request, null, true);
        clientSession.newStream(frame, Stream.Listener.AUTO_DISCARD);

        assertTrue(clientFailureLatch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testMaxHeaderListSizeExceededServerSendsGoAway() throws Exception
    {
        int maxHeadersSize = 512;
        start(new ServerSessionListener()
        {
            @Override
            public void onSettings(Session session, SettingsFrame frame)
            {
                ((HTTP2Session)session).getParser().getHpackDecoder().setMaxHeaderListSize(maxHeadersSize);
            }
        });

        CountDownLatch goAwayLatch = new CountDownLatch(1);
        Session clientSession = newClientSession(new Session.Listener()
        {
            @Override
            public void onGoAway(Session session, GoAwayFrame frame)
            {
                goAwayLatch.countDown();
            }
        });
        HttpFields requestHeaders = HttpFields.build()
            .put("X-Large", "x".repeat(maxHeadersSize * 2));
        MetaData.Request request = newRequest("GET", requestHeaders);
        HeadersFrame frame = new HeadersFrame(request, null, true);
        Stream stream = clientSession.newStream(frame, new Stream.Listener() {}).get(5, TimeUnit.SECONDS);

        // The request can be sent by the client, the server will reject it.
        // The spec suggests to send 431, but we do not want to "taint" the
        // HPACK context with large headers.
        assertNotNull(stream);

        // The server should send a GOAWAY.
        assertTrue(goAwayLatch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testMaxHeaderListSizeExceededByClient() throws Exception
    {
        int maxHeadersSize = 512;
        CountDownLatch goAwayLatch = new CountDownLatch(1);
        start(new ServerSessionListener()
        {
            @Override
            public Map<Integer, Integer> onPreface(Session session)
            {
                return Map.of(SettingsFrame.MAX_HEADER_LIST_SIZE, maxHeadersSize);
            }

            @Override
            public void onGoAway(Session session, GoAwayFrame frame)
            {
                goAwayLatch.countDown();
            }
        });

        CountDownLatch clientSettingsLatch = new CountDownLatch(1);
        Session clientSession = newClientSession(new Session.Listener()
        {
            @Override
            public void onSettings(Session session, SettingsFrame settingsFrame)
            {
                clientSettingsLatch.countDown();
            }
        });
        assertTrue(clientSettingsLatch.await(5, TimeUnit.SECONDS));

        HttpFields requestHeaders = HttpFields.build()
            .put("X-Large", "x".repeat(maxHeadersSize * 2));
        MetaData.Request request = newRequest("GET", requestHeaders);
        HeadersFrame frame = new HeadersFrame(request, null, true);

        Throwable failure = assertThrows(ExecutionException.class,
            () -> clientSession.newStream(frame, new Stream.Listener() {}).get(5, TimeUnit.SECONDS))
            .getCause();
        // The HPACK context is compromised trying to encode the large header.
        assertThat(failure, Matchers.instanceOf(HpackException.SessionException.class));

        assertTrue(goAwayLatch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testMaxHeaderListSizeCappedByClient() throws Exception
    {
        int maxHeadersSize = 2 * 1024;
        CountDownLatch goAwayLatch = new CountDownLatch(1);
        start(new ServerSessionListener()
        {
            @Override
            public Map<Integer, Integer> onPreface(Session session)
            {
                return Map.of(SettingsFrame.MAX_HEADER_LIST_SIZE, maxHeadersSize);
            }

            @Override
            public void onGoAway(Session session, GoAwayFrame frame)
            {
                goAwayLatch.countDown();
            }
        });
        http2Client.setMaxRequestHeadersSize(maxHeadersSize / 2);

        CountDownLatch clientSettingsLatch = new CountDownLatch(1);
        Session clientSession = newClientSession(new Session.Listener()
        {
            @Override
            public void onSettings(Session session, SettingsFrame settingsFrame)
            {
                clientSettingsLatch.countDown();
            }
        });
        assertTrue(clientSettingsLatch.await(5, TimeUnit.SECONDS));

        HttpFields requestHeaders = HttpFields.build()
            .put("X-Large", "x".repeat(maxHeadersSize - 256)); // 256 bytes to account for the other headers
        MetaData.Request request = newRequest("GET", requestHeaders);
        HeadersFrame frame = new HeadersFrame(request, null, true);

        Throwable failure = assertThrows(ExecutionException.class,
            () -> clientSession.newStream(frame, new Stream.Listener() {}).get(5, TimeUnit.SECONDS))
            .getCause();
        // The HPACK context is compromised trying to encode the large header.
        assertThat(failure, Matchers.instanceOf(HpackException.SessionException.class));

        assertTrue(goAwayLatch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testMaxHeaderListSizeExceededByServer() throws Exception
    {
        int maxHeadersSize = 512;
        AtomicReference<CompletableFuture<Stream>> responseRef = new AtomicReference<>();
        CountDownLatch settingsLatch = new CountDownLatch(2);
        start(new ServerSessionListener()
        {
            @Override
            public void onSettings(Session session, SettingsFrame frame)
            {
                settingsLatch.countDown();
            }

            @Override
            public Stream.Listener onNewStream(Stream stream, HeadersFrame frame)
            {
                HttpFields responseHeaders = HttpFields.build()
                    .put("X-Large", "x".repeat(maxHeadersSize * 2));
                MetaData.Response response = new MetaData.Response(HttpStatus.OK_200, null, HttpVersion.HTTP_2, responseHeaders);
                responseRef.set(stream.headers(new HeadersFrame(stream.getId(), response, null, true)));
                return null;
            }
        });

        CountDownLatch goAwayLatch = new CountDownLatch(1);
        Session clientSession = newClientSession(new Session.Listener()
        {
            @Override
            public void onSettings(Session session, SettingsFrame frame)
            {
                settingsLatch.countDown();
            }

            @Override
            public Map<Integer, Integer> onPreface(Session session)
            {
                return Map.of(SettingsFrame.MAX_HEADER_LIST_SIZE, maxHeadersSize);
            }

            @Override
            public void onGoAway(Session session, GoAwayFrame frame)
            {
                goAwayLatch.countDown();
            }
        });
        assertTrue(settingsLatch.await(5, TimeUnit.SECONDS));

        MetaData.Request request = newRequest("GET", HttpFields.EMPTY);
        HeadersFrame frame = new HeadersFrame(request, null, true);
        clientSession.newStream(frame, new Stream.Listener() {});

        CompletableFuture<Stream> completable = await().atMost(5, TimeUnit.SECONDS).until(responseRef::get, notNullValue());
        Throwable failure = assertThrows(ExecutionException.class, () -> completable.get(5, TimeUnit.SECONDS)).getCause();
        assertThat(failure, Matchers.instanceOf(HpackException.SessionException.class));

        assertTrue(goAwayLatch.await(5, TimeUnit.SECONDS));
    }
}
