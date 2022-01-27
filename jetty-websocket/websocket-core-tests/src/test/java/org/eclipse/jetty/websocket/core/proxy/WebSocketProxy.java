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

package org.eclipse.jetty.websocket.core.proxy;

import java.io.IOException;
import java.net.URI;
import java.nio.channels.ClosedChannelException;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;

import org.eclipse.jetty.util.BlockingArrayQueue;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.websocket.core.CloseStatus;
import org.eclipse.jetty.websocket.core.CoreSession;
import org.eclipse.jetty.websocket.core.Frame;
import org.eclipse.jetty.websocket.core.FrameHandler;
import org.eclipse.jetty.websocket.core.OpCode;
import org.eclipse.jetty.websocket.core.client.WebSocketCoreClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class WebSocketProxy
{
    protected static final Logger LOG = LoggerFactory.getLogger(WebSocketProxy.class);

    enum State
    {
        NOT_OPEN,
        CONNECTING,
        OPEN,
        ISHUT,
        OSHUT,
        CLOSED,
        FAILED
    }

    private final Object lock = new Object();
    private final WebSocketCoreClient client;
    private final URI serverUri;

    public Client2Proxy client2Proxy = new Client2Proxy();
    public Server2Proxy server2Proxy = new Server2Proxy();

    public WebSocketProxy(WebSocketCoreClient client, URI serverUri)
    {
        this.client = client;
        this.serverUri = serverUri;
    }

    class Client2Proxy implements FrameHandler
    {
        private CoreSession client;
        private State state = State.NOT_OPEN;

        private Callback closeCallback;
        private Throwable error;

        public BlockingQueue<Frame> receivedFrames = new BlockingArrayQueue<>();
        protected CountDownLatch closed = new CountDownLatch(1);

        public State getState()
        {
            synchronized (this)
            {
                return state;
            }
        }

        @Override
        public void onOpen(CoreSession coreSession, Callback callback)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("[{}] onOpen {}", toString(), coreSession);

            Throwable failure = null;
            synchronized (lock)
            {
                switch (state)
                {
                    case NOT_OPEN:
                        state = State.CONNECTING;
                        client = coreSession;
                        break;

                    default:
                        failure = new IllegalStateException(state.name());
                        break;
                }
            }

            if (failure != null)
                callback.failed(failure);
            else
                server2Proxy.connect(Callback.from(() -> onOpenSuccess(callback), (t) -> onOpenFail(callback, t)));
        }

        private void onOpenSuccess(Callback callback)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("[{}] onOpenSuccess", toString());

            Throwable failure = null;
            synchronized (lock)
            {
                switch (state)
                {
                    case CONNECTING:
                        state = State.OPEN;
                        break;

                    case FAILED:
                        failure = error;
                        break;

                    default:
                        failure = new IllegalStateException(state.name());
                        break;
                }
            }

            if (failure != null)
                server2Proxy.fail(failure, callback);
            else
                callback.succeeded();
        }

        private void onOpenFail(Callback callback, Throwable t)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("[{}] onOpenFail {}", toString(), t);

            Throwable failure = t;
            synchronized (lock)
            {
                switch (state)
                {
                    case CONNECTING:
                        state = State.FAILED;
                        error = t;
                        break;

                    case FAILED:
                        failure = error;
                        failure.addSuppressed(t);
                        break;

                    default:
                        failure = new IllegalStateException(state.name());
                        break;
                }
            }

            callback.failed(failure);
        }

        @Override
        public void onFrame(Frame frame, Callback callback)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("[{}] onFrame {}", toString(), frame);
            receivedFrames.offer(Frame.copy(frame));

            Callback sendCallback = callback;
            Throwable failure = null;
            synchronized (lock)
            {
                switch (state)
                {
                    case OPEN:
                        if (frame.getOpCode() == OpCode.CLOSE)
                        {
                            state = State.ISHUT;
                            // the callback is saved until a close response comes in sendFrame from Server2Proxy
                            // if the callback was completed here then core would send its own close response
                            closeCallback = callback;
                            sendCallback = Callback.from(() -> {}, callback::failed);
                        }
                        break;

                    case OSHUT:
                        if (frame.getOpCode() == OpCode.CLOSE)
                            state = State.CLOSED;
                        break;

                    case FAILED:
                        failure = error;
                        break;

                    default:
                        failure = new IllegalStateException(state.name());
                        break;
                }
            }

            if (failure != null)
                callback.failed(failure);
            else
                server2Proxy.send(frame, sendCallback);
        }

        @Override
        public void onError(Throwable failure, Callback callback)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("[{}] onError {}", toString(), failure);

            boolean failServer2Proxy;
            synchronized (lock)
            {
                switch (state)
                {
                    case FAILED:
                    case CLOSED:
                        failServer2Proxy = false;
                        break;

                    default:
                        state = State.FAILED;
                        error = failure;
                        failServer2Proxy = true;
                        break;
                }
            }

            if (failServer2Proxy)
                server2Proxy.fail(failure, callback);
            else
                callback.failed(failure);
        }

        public void fail(Throwable failure, Callback callback)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("[{}] fail {}", toString(), failure);

            Callback sendCallback = null;
            synchronized (lock)
            {
                switch (state)
                {
                    case OPEN:
                        state = State.FAILED;
                        sendCallback = Callback.from(callback, failure);
                        break;

                    case ISHUT:
                        state = State.FAILED;
                        Callback doubleCallback = Callback.from(callback, closeCallback);
                        sendCallback = Callback.from(doubleCallback, failure);
                        break;

                    default:
                        state = State.FAILED;
                        break;
                }
            }

            if (sendCallback != null)
                client.close(CloseStatus.SHUTDOWN, failure.getMessage(), sendCallback);
            else
                callback.failed(failure);
        }

        @Override
        public void onClosed(CloseStatus closeStatus, Callback callback)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("[{}] onClosed {}", toString(), closeStatus);

            boolean abnormalClose = false;
            synchronized (lock)
            {
                switch (state)
                {
                    case CLOSED:
                        break;

                    default:
                        abnormalClose = true;
                        break;
                }
            }

            if (abnormalClose)
                server2Proxy.fail(new ClosedChannelException(), Callback.NOOP);

            closed.countDown();
            callback.succeeded();
        }

        public void send(Frame frame, Callback callback)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("[{}] send {}", toString(), frame);

            Callback sendCallback = callback;
            Throwable failure = null;
            synchronized (lock)
            {
                switch (state)
                {
                    case OPEN:
                        if (frame.getOpCode() == OpCode.CLOSE)
                            state = State.OSHUT;
                        break;

                    case ISHUT:
                        if (frame.getOpCode() == OpCode.CLOSE)
                        {
                            state = State.CLOSED;
                            sendCallback = Callback.from(callback, closeCallback);
                        }
                        break;

                    case FAILED:
                        failure = error;
                        break;

                    default:
                        failure = new IllegalStateException(state.name());
                        break;
                }
            }

            if (failure != null)
                callback.failed(failure);
            else
                client.sendFrame(frame, sendCallback, false);
        }

        @Override
        public String toString()
        {
            return "Client2Proxy:" + getState();
        }
    }

    class Server2Proxy implements FrameHandler
    {
        private CoreSession server;
        private State state = State.NOT_OPEN;

        private Callback closeCallback;
        private Throwable error;

        public BlockingQueue<Frame> receivedFrames = new BlockingArrayQueue<>();
        protected CountDownLatch closed = new CountDownLatch(1);

        public State getState()
        {
            synchronized (this)
            {
                return state;
            }
        }

        public void connect(Callback callback)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("[{}] connect", toString());

            Throwable failure = null;
            synchronized (lock)
            {
                switch (state)
                {
                    case NOT_OPEN:
                        try
                        {
                            state = State.CONNECTING;
                            client.connect(this, serverUri).whenComplete((s, t) ->
                            {
                                if (t != null)
                                    onConnectFailure(t, callback);
                                else
                                    onConnectSuccess(s, callback);
                            });
                        }
                        catch (IOException e)
                        {
                            state = State.FAILED;
                            error = e;
                            failure = e;
                        }
                        break;

                    case FAILED:
                        failure = error;
                        break;

                    default:
                        state = State.FAILED;
                        error = new IllegalStateException(state.name());
                        failure = error;
                        break;
                }
            }

            if (failure != null)
                callback.failed(failure);
        }

        private void onConnectSuccess(CoreSession coreSession, Callback callback)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("[{}] onConnectSuccess {}", toString(), coreSession);

            Throwable failure = null;
            synchronized (lock)
            {
                switch (state)
                {
                    case OPEN:
                        break;

                    case FAILED:
                        failure = error;
                        break;

                    default:
                        state = State.FAILED;
                        error = new IllegalStateException(state.name());
                        failure = error;
                        break;
                }
            }

            if (failure != null)
                coreSession.close(CloseStatus.SHUTDOWN, failure.getMessage(), Callback.from(callback, failure));
            else
                callback.succeeded();
        }

        private void onConnectFailure(Throwable t, Callback callback)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("[{}] onConnectFailure {}", toString(), t);

            Throwable failure = t;
            synchronized (lock)
            {
                switch (state)
                {
                    case CONNECTING:
                        state = State.FAILED;
                        error = t;
                        break;

                    case FAILED:
                        failure = error;
                        break;

                    default:
                        state = State.FAILED;
                        error = new IllegalStateException(state.name());
                        failure = error;
                        break;
                }
            }

            callback.failed(failure);
        }

        @Override
        public void onOpen(CoreSession coreSession, Callback callback)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("[{}] onOpen {}", toString(), coreSession);

            Throwable failure = null;
            synchronized (lock)
            {
                switch (state)
                {
                    case CONNECTING:
                        state = State.OPEN;
                        server = coreSession;
                        break;

                    case FAILED:
                        failure = error;
                        break;

                    default:
                        failure = new IllegalStateException(state.name());
                        break;
                }
            }

            if (failure != null)
                callback.failed(failure);
            else
                callback.succeeded();
        }

        @Override
        public void onFrame(Frame frame, Callback callback)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("[{}] onFrame {}", toString(), frame);
            receivedFrames.offer(Frame.copy(frame));

            Callback sendCallback = callback;
            Throwable failure = null;
            synchronized (lock)
            {
                switch (state)
                {
                    case OPEN:
                        if (frame.getOpCode() == OpCode.CLOSE)
                        {
                            state = State.ISHUT;
                            closeCallback = callback;
                            sendCallback = Callback.from(() -> {}, callback::failed);
                        }
                        break;

                    case OSHUT:
                        if (frame.getOpCode() == OpCode.CLOSE)
                            state = State.CLOSED;
                        break;

                    case FAILED:
                        failure = error;
                        break;

                    default:
                        failure = new IllegalStateException(state.name());
                        break;
                }
            }

            if (failure != null)
                callback.failed(failure);
            else
                client2Proxy.send(frame, sendCallback);

        }

        @Override
        public void onError(Throwable failure, Callback callback)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("[{}] onError {}", toString(), failure);

            boolean failClient2Proxy = false;
            synchronized (lock)
            {
                switch (state)
                {
                    case FAILED:
                    case CLOSED:
                        break;

                    default:
                        state = State.FAILED;
                        error = failure;
                        failClient2Proxy = true;
                        break;
                }
            }

            if (failClient2Proxy)
                client2Proxy.fail(failure, callback);
            else
                callback.failed(failure);
        }

        @Override
        public void onClosed(CloseStatus closeStatus, Callback callback)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("[{}] onClosed {}", toString(), closeStatus);

            boolean abnormalClose = false;
            synchronized (lock)
            {
                switch (state)
                {
                    case CLOSED:
                        break;

                    default:
                        abnormalClose = true;
                        break;
                }
            }

            if (abnormalClose)
                client2Proxy.fail(new ClosedChannelException(), Callback.NOOP);

            closed.countDown();
            callback.succeeded();
        }

        public void fail(Throwable failure, Callback callback)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("[{}] fail {}", toString(), failure);

            Callback sendCallback = null;
            synchronized (lock)
            {
                switch (state)
                {
                    case OPEN:
                        state = State.FAILED;
                        sendCallback = Callback.from(callback, failure);
                        break;

                    case ISHUT:
                        state = State.FAILED;
                        Callback doubleCallback = Callback.from(callback, closeCallback);
                        sendCallback =  Callback.from(doubleCallback, failure);
                        break;

                    default:
                        state = State.FAILED;
                        break;
                }
            }

            if (sendCallback != null)
                server.close(CloseStatus.SHUTDOWN, failure.getMessage(), sendCallback);
            else
                callback.failed(failure);
        }

        public void send(Frame frame, Callback callback)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("[{}] send {}", toString(), frame);

            Callback sendCallback = callback;
            Throwable failure = null;
            synchronized (lock)
            {
                switch (state)
                {
                    case OPEN:
                        if (frame.getOpCode() == OpCode.CLOSE)
                            state = State.OSHUT;
                        break;

                    case ISHUT:
                        if (frame.getOpCode() == OpCode.CLOSE)
                        {
                            state = State.CLOSED;
                            sendCallback = Callback.from(callback, closeCallback);
                        }
                        break;

                    case FAILED:
                        failure = error;
                        break;

                    default:
                        failure = new IllegalStateException(state.name());
                        break;
                }
            }

            if (failure != null)
                callback.failed(failure);
            else
                server.sendFrame(frame, sendCallback, false);
        }

        @Override
        public String toString()
        {
            return "Server2Proxy:" + getState();
        }
    }
}
