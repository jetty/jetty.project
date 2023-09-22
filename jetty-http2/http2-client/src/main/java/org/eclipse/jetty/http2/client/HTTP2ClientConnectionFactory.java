//
//  ========================================================================
//  Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.http2.client;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executor;

import org.eclipse.jetty.http2.FlowControlStrategy;
import org.eclipse.jetty.http2.HTTP2Connection;
import org.eclipse.jetty.http2.ISession;
import org.eclipse.jetty.http2.api.Session;
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
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.util.thread.Scheduler;

public class HTTP2ClientConnectionFactory implements ClientConnectionFactory
{
    public static final String CLIENT_CONTEXT_KEY = "http2.client";
    public static final String BYTE_BUFFER_POOL_CONTEXT_KEY = "http2.client.byteBufferPool";
    public static final String EXECUTOR_CONTEXT_KEY = "http2.client.executor";
    public static final String SCHEDULER_CONTEXT_KEY = "http2.client.scheduler";
    public static final String SESSION_LISTENER_CONTEXT_KEY = "http2.client.sessionListener";
    public static final String SESSION_PROMISE_CONTEXT_KEY = "http2.client.sessionPromise";

    private final Connection.Listener connectionListener = new ConnectionListener();

    @Override
    public Connection newConnection(EndPoint endPoint, Map<String, Object> context)
    {
        HTTP2Client client = (HTTP2Client)context.get(CLIENT_CONTEXT_KEY);
        ByteBufferPool byteBufferPool = client.getByteBufferPool();
        Executor executor = client.getExecutor();
        Scheduler scheduler = client.getScheduler();
        Session.Listener listener = (Session.Listener)context.get(SESSION_LISTENER_CONTEXT_KEY);
        @SuppressWarnings("unchecked")
        Promise<Session> sessionPromise = (Promise<Session>)context.get(SESSION_PROMISE_CONTEXT_KEY);

        Generator generator = new Generator(byteBufferPool, client.getMaxHeaderBlockFragment());
        FlowControlStrategy flowControl = client.getFlowControlStrategyFactory().newFlowControlStrategy();

        Parser parser = new Parser(byteBufferPool, client.getMaxResponseHeadersSize());
        parser.setMaxFrameSize(client.getMaxFrameSize());
        parser.setMaxSettingsKeys(client.getMaxSettingsKeys());

        HTTP2ClientSession session = new HTTP2ClientSession(scheduler, endPoint, parser, generator, listener, flowControl);
        session.setMaxRemoteStreams(client.getMaxConcurrentPushedStreams());
        session.setMaxEncoderTableCapacity(client.getMaxEncoderTableCapacity());
        long streamIdleTimeout = client.getStreamIdleTimeout();
        if (streamIdleTimeout > 0)
            session.setStreamIdleTimeout(streamIdleTimeout);

        HTTP2ClientConnection connection = new HTTP2ClientConnection(client, byteBufferPool, executor, endPoint,
            session, client.getInputBufferSize(), sessionPromise, listener);
        connection.addListener(connectionListener);
        parser.init(connection.wrapParserListener(session));

        return customize(connection, context);
    }

    private static class HTTP2ClientConnection extends HTTP2Connection implements Callback
    {
        private final HTTP2Client client;
        private final Promise<Session> promise;
        private final Session.Listener listener;

        private HTTP2ClientConnection(HTTP2Client client, ByteBufferPool byteBufferPool, Executor executor, EndPoint endpoint, ISession session, int bufferSize, Promise<Session> promise, Session.Listener listener)
        {
            super(byteBufferPool, executor, endpoint, session, bufferSize);
            this.client = client;
            this.promise = promise;
            this.listener = listener;
        }

        @Override
        public void onOpen()
        {
            Map<Integer, Integer> settings = listener.onPreface(getSession());
            if (settings == null)
                settings = new HashMap<>();

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
                    if (v == Frame.DEFAULT_MAX_LENGTH)
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

            ISession session = getSession();

            int windowDelta = client.getInitialSessionRecvWindow() - FlowControlStrategy.DEFAULT_WINDOW_SIZE;
            session.updateRecvWindow(windowDelta);
            if (windowDelta > 0)
                session.frames(null, Arrays.asList(prefaceFrame, settingsFrame, new WindowUpdateFrame(0, windowDelta)), this);
            else
                session.frames(null, Arrays.asList(prefaceFrame, settingsFrame), this);
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
            http2Connection.client.addManaged((LifeCycle)http2Connection.getSession());
        }

        @Override
        public void onClosed(Connection connection)
        {
            HTTP2ClientConnection http2Connection = (HTTP2ClientConnection)connection;
            http2Connection.client.removeBean(http2Connection.getSession());
        }
    }
}
