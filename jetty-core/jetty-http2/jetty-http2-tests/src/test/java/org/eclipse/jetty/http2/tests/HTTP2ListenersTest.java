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

import java.util.Collection;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.eclipse.jetty.client.ContentResponse;
import org.eclipse.jetty.client.StringRequestContent;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http2.ErrorCode;
import org.eclipse.jetty.http2.HTTP2Session;
import org.eclipse.jetty.http2.api.Session;
import org.eclipse.jetty.http2.frames.Frame;
import org.eclipse.jetty.http2.frames.FrameType;
import org.eclipse.jetty.http2.frames.PingFrame;
import org.eclipse.jetty.http2.server.AbstractHTTP2ServerConnectionFactory;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.thread.Scheduler;
import org.junit.jupiter.api.Test;

import static org.awaitility.Awaitility.await;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInRelativeOrder;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.sameInstance;
import static org.hamcrest.Matchers.startsWith;

public class HTTP2ListenersTest extends AbstractTest
{
    @Test
    public void testFrameListener() throws Exception
    {
        start(new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                Content.copy(request, response, callback);
                return true;
            }
        });
        // Set up the frame listener on the server.
        ListFrameListener serverFrameListener = new ListFrameListener();
        connector.getConnectionFactory(AbstractHTTP2ServerConnectionFactory.class).addBean(serverFrameListener);

        // Set up the frame listener on the client.
        ListFrameListener clientFrameListener = new ListFrameListener();
        http2Client.addBean(clientFrameListener);

        String content = "data";
        ContentResponse response = httpClient.newRequest("localhost", connector.getLocalPort())
            .body(new StringRequestContent(content))
            .timeout(5, TimeUnit.SECONDS)
            .send();

        assertThat(response.getStatus(), is(HttpStatus.OK_200));
        assertThat(response.getContentAsString(), is(content));

        assertThat(serverFrameListener.incoming, not(empty()));
        assertThat(clientFrameListener.incoming, not(empty()));

        List<FrameType> clientOutgoingTypes = ListFrameListener.toFrameTypes(clientFrameListener.outgoing);
        List<FrameType> clientIncomingTypes = ListFrameListener.toFrameTypes(clientFrameListener.incoming);
        List<FrameType> serverOutgoingTypes = ListFrameListener.toFrameTypes(serverFrameListener.outgoing);
        List<FrameType> serverIncomingTypes = ListFrameListener.toFrameTypes(serverFrameListener.incoming);

        // Verify that what one side sent was what the other side received.
        assertThat(clientOutgoingTypes, equalTo(serverIncomingTypes));
        assertThat(serverOutgoingTypes, equalTo(clientIncomingTypes));

        // First frame sent must be a SETTINGS.
        assertThat(clientOutgoingTypes.get(0), is(FrameType.SETTINGS));
        assertThat(serverOutgoingTypes.get(0), is(FrameType.SETTINGS));

        // There must be at least one HEADERS and one DATA.
        assertThat(clientOutgoingTypes, containsInRelativeOrder(FrameType.HEADERS, FrameType.DATA));
        assertThat(serverOutgoingTypes, containsInRelativeOrder(FrameType.HEADERS, FrameType.DATA));
    }

    @Test
    public void testLifeCycleListener() throws Exception
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
        // Set up the lifecycle listener on the server.
        ListLifeCycleListener serverLifeCycleListener = new ListLifeCycleListener();
        connector.getConnectionFactory(AbstractHTTP2ServerConnectionFactory.class).addBean(serverLifeCycleListener);

        // Set up the lifecycle listener on the client.
        ListLifeCycleListener clientLifeCycleListener = new ListLifeCycleListener();
        http2Client.addBean(clientLifeCycleListener);

        ContentResponse response = httpClient.newRequest("localhost", connector.getLocalPort())
            .timeout(5, TimeUnit.SECONDS)
            .send();

        assertThat(response.getStatus(), is(HttpStatus.OK_200));

        assertThat(serverLifeCycleListener.sessions, hasSize(1));
        assertThat(clientLifeCycleListener.sessions, hasSize(1));

        clientLifeCycleListener.sessions.get(0).close(ErrorCode.NO_ERROR.code, "close", Callback.NOOP);

        await().atMost(5, TimeUnit.SECONDS).until(() -> serverLifeCycleListener.sessions, hasSize(0));
        await().atMost(5, TimeUnit.SECONDS).until(() -> clientLifeCycleListener.sessions, hasSize(0));
    }

    @Test
    public void testLifeCycleFrameListener() throws Exception
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
        // Set up the lifecycle listener on the server.
        ListLifeCycleFrameListener serverLifeCycleFrameListener = new ListLifeCycleFrameListener();
        connector.getConnectionFactory(AbstractHTTP2ServerConnectionFactory.class).addBean(serverLifeCycleFrameListener);

        // Set up the lifecycle listener on the client.
        ListLifeCycleFrameListener clientLifeCycleFrameListener = new ListLifeCycleFrameListener();
        http2Client.addBean(clientLifeCycleFrameListener);

        ContentResponse response = httpClient.newRequest("localhost", connector.getLocalPort())
            .timeout(5, TimeUnit.SECONDS)
            .send();

        assertThat(response.getStatus(), is(HttpStatus.OK_200));

        assertThat(serverLifeCycleFrameListener.events, not(empty()));
        assertThat(clientLifeCycleFrameListener.events, not(empty()));

        assertThat(serverLifeCycleFrameListener.events.toString(), serverLifeCycleFrameListener.events.get(0), is("OPEN"));
        assertThat(clientLifeCycleFrameListener.events.get(0), is("OPEN"));

        assertThat(serverLifeCycleFrameListener.events.get(1), startsWith("SETTINGS"));
        assertThat(clientLifeCycleFrameListener.events.get(1), startsWith("SETTINGS"));

        httpClient.stop();

        await().atMost(5, TimeUnit.SECONDS).until(() ->
        {
            int serverEventsSize = serverLifeCycleFrameListener.events.size();
            return serverLifeCycleFrameListener.events.get(serverEventsSize - 1);
        }, is("CLOSE"));
        await().atMost(5, TimeUnit.SECONDS).until(() ->
        {
            int clientEventsSize = clientLifeCycleFrameListener.events.size();
            return clientLifeCycleFrameListener.events.get(clientEventsSize - 1);
        }, is("CLOSE"));
    }

    @Test
    public void testLowLevelPingFromHighLevelAPIs() throws Exception
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

        long pingInterval = 1000;
        long pingTimeout = 5000;

        class PingListenerFactory implements HTTP2Session.LifeCycleListener
        {
            @Override
            public void onOpen(Session session)
            {
                ((HTTP2Session)session).addEventListener(new PingListener());
            }

            private class PingListener implements HTTP2Session.FrameListener
            {
                private final AtomicBoolean firstPing = new AtomicBoolean();
                private Scheduler.Task task;

                @Override
                public void onIncomingFrame(Session session, Frame frame)
                {
                    switch (frame.getType())
                    {
                        case SETTINGS ->
                        {
                            if (firstPing.compareAndSet(false, true))
                                ping(session);
                        }
                        case PING ->
                        {
                            PingFrame pingFrame = (PingFrame)frame;
                            if (pingFrame.isReply() && task.cancel())
                                httpClient.getScheduler().schedule(() -> ping(session), pingInterval, TimeUnit.MILLISECONDS);
                        }
                    }
                }

                private void ping(Session session)
                {
                    task = httpClient.getScheduler().schedule(() -> close(session), pingTimeout, TimeUnit.MILLISECONDS);
                    session.ping(new PingFrame(0L, false), Callback.NOOP);
                }

                private void close(Session session)
                {
                    session.close(ErrorCode.NO_ERROR.code, "ping_timeout", Callback.NOOP);
                }
            }
        }

        http2Client.addBean(new PingListenerFactory());
        ListFrameListener clientFrameListener = new ListFrameListener();
        http2Client.addBean(clientFrameListener);

        // Configure the idle timeout, but it should never fire, since we continuously ping.
        httpClient.setIdleTimeout(2 * pingInterval);

        ContentResponse response = httpClient.newRequest("localhost", connector.getLocalPort())
            .timeout(5, TimeUnit.SECONDS)
            .send();

        assertThat(response.getStatus(), is(HttpStatus.OK_200));

        assertThat(clientFrameListener.incoming, not(empty()));

        Collection<EndPoint> serverEndPoints1 = connector.getConnectedEndPoints();
        assertThat(serverEndPoints1, hasSize(1));
        EndPoint serverEndPoint1 = serverEndPoints1.iterator().next();

        // Sleep for more than the idle timeout.
        // The PINGS should keep the connection alive.
        await().during(httpClient.getIdleTimeout() * 3 / 2, TimeUnit.MILLISECONDS).atMost(5, TimeUnit.SECONDS).until(() ->
        {
            Collection<EndPoint> serverEndPoints2 = connector.getConnectedEndPoints();
            assertThat(serverEndPoints2, hasSize(1));
            return serverEndPoints2.iterator().next();
        }, sameInstance(serverEndPoint1));

        List<FrameType> clientOutgoingFrameTypes = ListFrameListener.toFrameTypes(clientFrameListener.outgoing);
        // At least 2 PINGs have been sent.
        assertThat(clientOutgoingFrameTypes, containsInRelativeOrder(FrameType.PING, FrameType.PING));
        List<FrameType> clientIncomingFrameTypes = ListFrameListener.toFrameTypes(clientFrameListener.incoming);
        // At least 2 PINGs have been received.
        assertThat(clientIncomingFrameTypes, containsInRelativeOrder(FrameType.PING, FrameType.PING));
    }

    private static class ListFrameListener implements HTTP2Session.FrameListener
    {
        private final List<Frame> incoming = new CopyOnWriteArrayList<>();
        private final List<Frame> outgoing = new CopyOnWriteArrayList<>();

        @Override
        public void onIncomingFrame(Session session, Frame frame)
        {
            incoming.add(frame);
        }

        @Override
        public void onOutgoingFrame(Session session, Frame frame)
        {
            outgoing.add(frame);
        }

        private static List<FrameType> toFrameTypes(List<Frame> frames)
        {
            return frames.stream().map(Frame::getType).toList();
        }
    }

    private static class ListLifeCycleListener implements HTTP2Session.LifeCycleListener
    {
        private final List<Session> sessions = new CopyOnWriteArrayList<>();

        @Override
        public void onOpen(Session session)
        {
            sessions.add(session);
        }

        @Override
        public void onClose(Session session)
        {
            sessions.remove(session);
        }
    }

    private static class ListLifeCycleFrameListener implements HTTP2Session.LifeCycleListener, HTTP2Session.FrameListener
    {
        private final List<String> events = new CopyOnWriteArrayList<>();

        @Override
        public void onOpen(Session session)
        {
            events.add("OPEN");
        }

        @Override
        public void onIncomingFrame(Session session, Frame frame)
        {
            events.add(frame.getType().name() + "-IN");
        }

        @Override
        public void onOutgoingFrame(Session session, Frame frame)
        {
            events.add(frame.getType().name() + "-OUT");
        }

        @Override
        public void onClose(Session session)
        {
            events.add("CLOSE");
        }
    }
}
