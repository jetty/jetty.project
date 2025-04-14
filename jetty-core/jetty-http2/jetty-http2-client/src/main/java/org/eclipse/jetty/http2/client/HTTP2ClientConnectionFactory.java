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

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.jetty.http2.FlowControlStrategy;
import org.eclipse.jetty.http2.HTTP2Connection;
import org.eclipse.jetty.http2.HTTP2Session;
import org.eclipse.jetty.http2.api.Session;
import org.eclipse.jetty.http2.client.internal.HTTP2ClientSession;
import org.eclipse.jetty.http2.frames.Frame;
import org.eclipse.jetty.http2.frames.PrefaceFrame;
import org.eclipse.jetty.http2.frames.SettingsFrame;
import org.eclipse.jetty.http2.frames.WindowUpdateFrame;
import org.eclipse.jetty.http2.generator.Generator;
import org.eclipse.jetty.http2.hpack.HpackContext;
import org.eclipse.jetty.http2.parser.Parser;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.ClientConnectionFactory;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.Promise;

public class HTTP2ClientConnectionFactory implements ClientConnectionFactory
{
    public static final String CLIENT_CONTEXT_KEY = "org.eclipse.jetty.client.http2";
    public static final String SESSION_LISTENER_CONTEXT_KEY = "org.eclipse.jetty.client.http2.sessionListener";
    public static final String SESSION_PROMISE_CONTEXT_KEY = "org.eclipse.jetty.client.http2.sessionPromise";

    private final Connection.Listener connectionListener = new ConnectionListener();

    @Override
    public Connection newConnection(EndPoint endPoint, Map<String, Object> context)
    {
        HTTP2Client client = (HTTP2Client)context.get(CLIENT_CONTEXT_KEY);
        ByteBufferPool bufferPool = client.getByteBufferPool();
        Session.Listener listener = (Session.Listener)context.get(SESSION_LISTENER_CONTEXT_KEY);
        @SuppressWarnings("unchecked")
        Promise<Session> sessionPromise = (Promise<Session>)context.get(SESSION_PROMISE_CONTEXT_KEY);

        Generator generator = new Generator(bufferPool, client.isUseOutputDirectByteBuffers(), client.getMaxHeaderBlockFragment());
        generator.getHpackEncoder().setMaxHeaderListSize(client.getMaxRequestHeadersSize());

        FlowControlStrategy flowControl = client.getFlowControlStrategyFactory().newFlowControlStrategy();

        Parser parser = new Parser(bufferPool, client.getMaxResponseHeadersSize());
        parser.setMaxSettingsKeys(client.getMaxSettingsKeys());

        HTTP2ClientSession session = new HTTP2ClientSession(client.getScheduler(), endPoint, parser, generator, listener, flowControl);
        session.setMaxRemoteStreams(client.getMaxConcurrentPushedStreams());
        session.setMaxEncoderTableCapacity(client.getMaxEncoderTableCapacity());
        long streamIdleTimeout = client.getStreamIdleTimeout();
        if (streamIdleTimeout > 0)
            session.setStreamIdleTimeout(streamIdleTimeout);

        HTTP2ClientConnection connection = new HTTP2ClientConnection(client, endPoint, session, sessionPromise, listener);
        context.put(HTTP2Connection.class.getName(), connection);
        connection.addEventListener(connectionListener);
        client.getEventListeners().forEach(session::addEventListener);
        parser.init(connection);

        return customize(connection, context);
    }

    private static class HTTP2ClientConnection extends HTTP2Connection implements Callback
    {
        private final HTTP2Client client;
        private final Promise<Session> promise;
        private final Session.Listener listener;

        private HTTP2ClientConnection(HTTP2Client client, EndPoint endpoint, HTTP2ClientSession session, Promise<Session> sessionPromise, Session.Listener listener)
        {
            super(client.getByteBufferPool(), client.getExecutor(), endpoint, session, client.getInputBufferSize());
            this.client = client;
            this.promise = sessionPromise;
            this.listener = listener;
            setUseInputDirectByteBuffers(client.isUseInputDirectByteBuffers());
            setUseOutputDirectByteBuffers(client.isUseOutputDirectByteBuffers());
        }

        @Override
        public void onOpen()
        {
            HTTP2Session session = getSession();
            session.notifyLifeCycleOpen();

            Map<Integer, Integer> settings = listener.onPreface(session);
            settings = settings == null ? new HashMap<>() : new HashMap<>(settings);

            // Below we want to populate any settings to send to the server
            // that have a different default than what prescribed by the RFC.
            // Changing the configuration is done when the SETTINGS is sent.

            settings.compute(SettingsFrame.HEADER_TABLE_SIZE, (k, v) ->
            {
                if (v == null)
                {
                    v = client.getMaxDecoderTableCapacity();
                    if (v == HpackContext.DEFAULT_MAX_TABLE_CAPACITY)
                        v = null;
                }
                return v;
            });
            settings.computeIfAbsent(SettingsFrame.MAX_CONCURRENT_STREAMS, k -> client.getMaxConcurrentPushedStreams());
            settings.compute(SettingsFrame.INITIAL_WINDOW_SIZE, (k, v) ->
            {
                if (v == null)
                {
                    v = client.getInitialStreamRecvWindow();
                    if (v == FlowControlStrategy.DEFAULT_WINDOW_SIZE)
                        v = null;
                }
                return v;
            });
            settings.compute(SettingsFrame.MAX_FRAME_SIZE, (k, v) ->
            {
                if (v == null)
                {
                    v = client.getMaxFrameSize();
                    if (v == Frame.DEFAULT_MAX_SIZE)
                        v = null;
                }
                return v;
            });
            settings.compute(SettingsFrame.MAX_HEADER_LIST_SIZE, (k, v) ->
            {
                if (v == null)
                {
                    v = client.getMaxResponseHeadersSize();
                    if (v <= 0)
                        v = null;
                }
                return v;
            });

            PrefaceFrame prefaceFrame = new PrefaceFrame();
            SettingsFrame settingsFrame = new SettingsFrame(settings, false);

            int windowDelta = client.getInitialSessionRecvWindow() - FlowControlStrategy.DEFAULT_WINDOW_SIZE;
            session.updateRecvWindow(windowDelta);
            if (windowDelta > 0)
                session.frames(null, List.of(prefaceFrame, settingsFrame, new WindowUpdateFrame(0, windowDelta)), this);
            else
                session.frames(null, List.of(prefaceFrame, settingsFrame), this);
        }

        @Override
        public void succeeded()
        {
            super.onOpen();
            promise.succeeded(getSession());
            // Only start reading from server after we have sent the client preface,
            // otherwise we risk to read the server preface (a SETTINGS frame) and
            // reply to that before we have the chance to send the client preface.
            produce();
        }

        @Override
        public void failed(Throwable x)
        {
            close();
            promise.failed(x);
        }

        @Override
        public InvocationType getInvocationType()
        {
            return InvocationType.NON_BLOCKING;
        }
    }

    private static class ConnectionListener implements Connection.Listener
    {
        @Override
        public void onOpened(Connection connection)
        {
            HTTP2ClientConnection http2Connection = (HTTP2ClientConnection)connection;
            http2Connection.client.addManaged(http2Connection.getSession());
        }

        @Override
        public void onClosed(Connection connection)
        {
            HTTP2ClientConnection http2Connection = (HTTP2ClientConnection)connection;
            http2Connection.client.removeBean(http2Connection.getSession());
        }
    }
}
