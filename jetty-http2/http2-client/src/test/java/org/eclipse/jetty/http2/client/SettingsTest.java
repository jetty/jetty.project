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

package org.eclipse.jetty.http2.client;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
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
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class SettingsTest extends AbstractTest
{
    @Test
    public void testSettingsNonZeroStreamId() throws Exception
    {
        AtomicReference<CountDownLatch> serverSettingsLatch = new AtomicReference<>(null);
        CountDownLatch serverFailureLatch = new CountDownLatch(1);
        CountDownLatch serverCloseLatch = new CountDownLatch(1);
        start(new ServerSessionListener.Adapter()
        {
            @Override
            public void onSettings(Session session, SettingsFrame frame)
            {
                CountDownLatch latch = serverSettingsLatch.get();
                if (latch != null)
                    latch.countDown();
            }

            @Override
            public void onFailure(Session session, Throwable failure)
            {
                serverFailureLatch.countDown();
            }

            @Override
            public void onClose(Session session, GoAwayFrame frame)
            {
                serverCloseLatch.countDown();
            }
        });

        CountDownLatch clientGoAwayLatch = new CountDownLatch(1);
        CountDownLatch clientCloseLatch = new CountDownLatch(1);
        Session clientSession = newClient(new Session.Listener.Adapter()
        {
            @Override
            public void onGoAway(Session session, GoAwayFrame frame)
            {
                clientGoAwayLatch.countDown();
            }

            @Override
            public void onClose(Session session, GoAwayFrame frame)
            {
                clientCloseLatch.countDown();
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

        Assertions.assertFalse(serverSettingsLatch.get().await(1, TimeUnit.SECONDS));
        Assertions.assertTrue(serverFailureLatch.await(5, TimeUnit.SECONDS));
        Assertions.assertTrue(serverCloseLatch.await(5, TimeUnit.SECONDS));
        Assertions.assertTrue(clientGoAwayLatch.await(5, TimeUnit.SECONDS));
        Assertions.assertTrue(clientCloseLatch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testSettingsReplyWithPayload() throws Exception
    {
        AtomicReference<CountDownLatch> serverSettingsLatch = new AtomicReference<>(null);
        CountDownLatch serverFailureLatch = new CountDownLatch(1);
        CountDownLatch serverCloseLatch = new CountDownLatch(1);
        start(new ServerSessionListener.Adapter()
        {
            @Override
            public void onSettings(Session session, SettingsFrame frame)
            {
                CountDownLatch latch = serverSettingsLatch.get();
                if (latch != null)
                    latch.countDown();
            }

            @Override
            public void onFailure(Session session, Throwable failure)
            {
                serverFailureLatch.countDown();
            }

            @Override
            public void onClose(Session session, GoAwayFrame frame)
            {
                serverCloseLatch.countDown();
            }
        });

        CountDownLatch clientGoAwayLatch = new CountDownLatch(1);
        CountDownLatch clientCloseLatch = new CountDownLatch(1);
        Session clientSession = newClient(new Session.Listener.Adapter()
        {
            @Override
            public void onGoAway(Session session, GoAwayFrame frame)
            {
                clientGoAwayLatch.countDown();
            }

            @Override
            public void onClose(Session session, GoAwayFrame frame)
            {
                clientCloseLatch.countDown();
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

        Assertions.assertFalse(serverSettingsLatch.get().await(1, TimeUnit.SECONDS));
        Assertions.assertTrue(serverFailureLatch.await(5, TimeUnit.SECONDS));
        Assertions.assertTrue(serverCloseLatch.await(5, TimeUnit.SECONDS));
        Assertions.assertTrue(clientGoAwayLatch.await(5, TimeUnit.SECONDS));
        Assertions.assertTrue(clientCloseLatch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testInvalidEnablePush() throws Exception
    {
        CountDownLatch serverFailureLatch = new CountDownLatch(1);
        start(new ServerSessionListener.Adapter()
        {
            @Override
            public void onFailure(Session session, Throwable failure)
            {
                serverFailureLatch.countDown();
            }
        });

        newClient(new Session.Listener.Adapter()
        {
            @Override
            public Map<Integer, Integer> onPreface(Session session)
            {
                Map<Integer, Integer> settings = new HashMap<>();
                settings.put(SettingsFrame.ENABLE_PUSH, 2); // Invalid value.
                return settings;
            }
        });

        Assertions.assertTrue(serverFailureLatch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testServerSendsEnablePush() throws Exception
    {
        start(new ServerSessionListener.Adapter()
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
        newClient(new Session.Listener.Adapter()
        {
            @Override
            public void onFailure(Session session, Throwable failure)
            {
                clientFailureLatch.countDown();
            }
        });

        Assertions.assertTrue(clientFailureLatch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testServerCannotSendsPushPromiseWithPushDisabled() throws Exception
    {
        CountDownLatch serverPushFailureLatch = new CountDownLatch(1);
        start(new ServerSessionListener.Adapter()
        {
            @Override
            public Stream.Listener onNewStream(Stream stream, HeadersFrame frame)
            {
                MetaData.Response response = new MetaData.Response(HttpVersion.HTTP_2, HttpStatus.OK_200, HttpFields.EMPTY);
                stream.headers(new HeadersFrame(stream.getId(), response, null, true))
                    .thenAccept(s ->
                    {
                        MetaData.Request push = newRequest("GET", "/push", HttpFields.EMPTY);
                        try
                        {
                            s.push(new PushPromiseFrame(s.getId(), push), new Stream.Listener.Adapter());
                        }
                        catch (IllegalStateException x)
                        {
                            serverPushFailureLatch.countDown();
                        }
                    });
                return null;
            }
        });

        Session clientSession = newClient(new Session.Listener.Adapter()
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
        clientSession.newStream(frame, new Stream.Listener.Adapter()
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

        Assertions.assertTrue(serverPushFailureLatch.await(5, TimeUnit.SECONDS));
        Assertions.assertTrue(clientResponseLatch.await(5, TimeUnit.SECONDS));
        Assertions.assertFalse(clientPushLatch.await(1, TimeUnit.SECONDS));
    }

    @Test
    public void testClientReceivesPushPromiseWhenPushDisabled() throws Exception
    {
        start(new ServerSessionListener.Adapter()
        {
            @Override
            public Stream.Listener onNewStream(Stream stream, HeadersFrame frame)
            {
                try
                {
                    HTTP2Session session = (HTTP2Session)stream.getSession();
                    ByteBufferPool.Lease lease = new ByteBufferPool.Lease(connector.getByteBufferPool());
                    MetaData.Request push = newRequest("GET", "/push", HttpFields.EMPTY);
                    PushPromiseFrame pushFrame = new PushPromiseFrame(stream.getId(), 2, push);
                    session.getGenerator().control(lease, pushFrame);
                    session.getEndPoint().write(Callback.NOOP, lease.getByteBuffers().toArray(ByteBuffer[]::new));
                    return null;
                }
                catch (HpackException x)
                {
                    return null;
                }
            }
        });

        CountDownLatch clientFailureLatch = new CountDownLatch(1);
        Session clientSession = newClient(new Session.Listener.Adapter()
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
            public void onFailure(Session session, Throwable failure)
            {
                clientFailureLatch.countDown();
            }
        });

        // Wait until the server has finished the previous writes.
        Thread.sleep(1000);

        MetaData.Request request = newRequest("GET", HttpFields.EMPTY);
        HeadersFrame frame = new HeadersFrame(request, null, true);
        clientSession.newStream(frame, new Stream.Listener.Adapter());

        Assertions.assertTrue(clientFailureLatch.await(5, TimeUnit.SECONDS));
    }
}
