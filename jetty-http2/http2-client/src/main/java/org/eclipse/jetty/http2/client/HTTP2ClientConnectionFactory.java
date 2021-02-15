//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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
import org.eclipse.jetty.http2.frames.PrefaceFrame;
import org.eclipse.jetty.http2.frames.SettingsFrame;
import org.eclipse.jetty.http2.frames.WindowUpdateFrame;
import org.eclipse.jetty.http2.generator.Generator;
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
        ByteBufferPool byteBufferPool = (ByteBufferPool)context.get(BYTE_BUFFER_POOL_CONTEXT_KEY);
        Executor executor = (Executor)context.get(EXECUTOR_CONTEXT_KEY);
        Scheduler scheduler = (Scheduler)context.get(SCHEDULER_CONTEXT_KEY);
        Session.Listener listener = (Session.Listener)context.get(SESSION_LISTENER_CONTEXT_KEY);
        @SuppressWarnings("unchecked")
        Promise<Session> promise = (Promise<Session>)context.get(SESSION_PROMISE_CONTEXT_KEY);

        Generator generator = new Generator(byteBufferPool, client.getMaxDynamicTableSize(), client.getMaxHeaderBlockFragment());
        FlowControlStrategy flowControl = client.getFlowControlStrategyFactory().newFlowControlStrategy();
        HTTP2ClientSession session = new HTTP2ClientSession(scheduler, endPoint, generator, listener, flowControl);
        session.setMaxRemoteStreams(client.getMaxConcurrentPushedStreams());

        Parser parser = new Parser(byteBufferPool, session, 4096, 8192);
        parser.setMaxFrameLength(client.getMaxFrameLength());
        parser.setMaxSettingsKeys(client.getMaxSettingsKeys());

        HTTP2ClientConnection connection = new HTTP2ClientConnection(client, byteBufferPool, executor, endPoint,
            parser, session, client.getInputBufferSize(), promise, listener);
        connection.addListener(connectionListener);
        return customize(connection, context);
    }

    private static class HTTP2ClientConnection extends HTTP2Connection implements Callback
    {
        private final HTTP2Client client;
        private final Promise<Session> promise;
        private final Session.Listener listener;

        private HTTP2ClientConnection(HTTP2Client client, ByteBufferPool byteBufferPool, Executor executor, EndPoint endpoint, Parser parser, ISession session, int bufferSize, Promise<Session> promise, Session.Listener listener)
        {
            super(byteBufferPool, executor, endpoint, parser, session, bufferSize);
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
            settings.computeIfAbsent(SettingsFrame.INITIAL_WINDOW_SIZE, k -> client.getInitialStreamRecvWindow());
            settings.computeIfAbsent(SettingsFrame.MAX_CONCURRENT_STREAMS, k -> client.getMaxConcurrentPushedStreams());

            Integer maxFrameLength = settings.get(SettingsFrame.MAX_FRAME_SIZE);
            if (maxFrameLength != null)
                getParser().setMaxFrameLength(maxFrameLength);

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
