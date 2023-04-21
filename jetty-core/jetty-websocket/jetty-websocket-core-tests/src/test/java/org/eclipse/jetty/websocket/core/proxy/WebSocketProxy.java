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

package org.eclipse.jetty.websocket.core.proxy;

import java.io.IOException;
import java.net.URI;
import java.nio.channels.ClosedChannelException;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;

import org.eclipse.jetty.util.BlockingArrayQueue;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.thread.AutoLock;
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

    private final WebSocketCoreClient proxyClient;
    private final URI serverURI;

    public Client2Proxy client2Proxy = new Client2Proxy();
    public Proxy2Server proxy2Server = new Proxy2Server();

    public WebSocketProxy(WebSocketCoreClient proxyClient, URI serverURI)
    {
        this.proxyClient = proxyClient;
        this.serverURI = serverURI;
    }

    class Client2Proxy implements FrameHandler
    {
        private final AutoLock lock = new AutoLock();
        private CoreSession client2ProxySession;
        private State state = State.NOT_OPEN;

        private Callback closeCallback;
        private Throwable error;

        public BlockingQueue<Frame> receivedFrames = new BlockingArrayQueue<>();
        protected CountDownLatch closed = new CountDownLatch(1);

        public State getState()
        {
            try (AutoLock ignored = lock.lock())
            {
                return state;
            }
        }

        @Override
        public void onOpen(CoreSession coreSession, Callback callback)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("[{}] onOpen {}", this, coreSession);

            Throwable failure = null;
            try (AutoLock ignored = lock.lock())
            {
                switch (state)
                {
                    case NOT_OPEN:
                        state = State.CONNECTING;
                        client2ProxySession = coreSession;
                        break;

                    default:
                        failure = new IllegalStateException(state.name());
                        break;
                }
            }

            if (failure != null)
                callback.failed(failure);
            else
                proxy2Server.connect(Callback.from(() -> onOpenSuccess(callback), (t) -> onOpenFail(callback, t)));
        }

        private void onOpenSuccess(Callback callback)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("[{}] onOpenSuccess", this);

            Throwable failure = null;
            try (AutoLock ignored = lock.lock())
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
            {
                proxy2Server.fail(failure, callback);
            }
            else
            {
                callback.succeeded();
                client2ProxySession.demand(1);
            }
        }

        private void onOpenFail(Callback callback, Throwable t)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("[{}] onOpenFail", this, t);

            Throwable failure = t;
            try (AutoLock ignored = lock.lock())
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
                LOG.debug("[{}] onFrame {}", this, frame);
            receivedFrames.offer(Frame.copy(frame));

            boolean demand = false;
            Callback sendCallback = callback;
            Throwable failure = null;
            try (AutoLock ignored = lock.lock())
            {
                switch (state)
                {
                    case OPEN:
                        if (frame.getOpCode() == OpCode.CLOSE)
                        {
                            state = State.ISHUT;
                            // the callback is saved until a close response comes in sendFrame from Proxy2Server
                            // if the callback was completed here then core would send its own close response
                            closeCallback = callback;
                            sendCallback = Callback.from(() -> {}, callback::failed);
                        }
                        else
                        {
                            demand = true;
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
            {
                callback.failed(failure);
            }
            else
            {
                if (demand)
                {
                    Callback c = sendCallback;
                    proxy2Server.send(frame, Callback.from(() ->
                    {
                        c.succeeded();
                        client2ProxySession.demand(1);
                    }, c::failed));
                }
                else
                {
                    proxy2Server.send(frame, sendCallback);
                }
            }
        }

        @Override
        public void onError(Throwable failure, Callback callback)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("[{}] onError", this, failure);

            boolean failServer2Proxy;
            try (AutoLock ignored = lock.lock())
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
                proxy2Server.fail(failure, callback);
            else
                callback.failed(failure);
        }

        public void fail(Throwable failure, Callback callback)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("[{}] fail", this, failure);

            Callback sendCallback = null;
            try (AutoLock ignored = lock.lock())
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
                client2ProxySession.close(CloseStatus.SHUTDOWN, failure.getMessage(), sendCallback);
            else
                callback.failed(failure);
        }

        @Override
        public void onClosed(CloseStatus closeStatus, Callback callback)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("[{}] onClosed {}", this, closeStatus);

            boolean abnormalClose = false;
            try (AutoLock ignored = lock.lock())
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
                proxy2Server.fail(new ClosedChannelException(), Callback.NOOP);

            closed.countDown();
            callback.succeeded();
        }

        public void send(Frame frame, Callback callback)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("[{}] send {}", this, frame);

            Callback sendCallback = callback;
            Throwable failure = null;
            try (AutoLock ignored = lock.lock())
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
                client2ProxySession.sendFrame(frame, sendCallback, false);
        }

        @Override
        public String toString()
        {
            return "Client2Proxy:" + getState();
        }
    }

    class Proxy2Server implements FrameHandler
    {
        private final AutoLock lock = new AutoLock();
        private CoreSession proxy2ServerSession;
        private State state = State.NOT_OPEN;

        private Callback closeCallback;
        private Throwable error;

        public BlockingQueue<Frame> receivedFrames = new BlockingArrayQueue<>();
        protected CountDownLatch closed = new CountDownLatch(1);

        public State getState()
        {
            try (AutoLock ignored = lock.lock())
            {
                return state;
            }
        }

        public void connect(Callback callback)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("[{}] connect", this);

            Throwable failure = null;
            try (AutoLock ignored = lock.lock())
            {
                switch (state)
                {
                    case NOT_OPEN:
                        try
                        {
                            state = State.CONNECTING;
                            proxyClient.connect(this, serverURI).whenComplete((s, t) ->
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
                LOG.debug("[{}] onConnectSuccess {}", this, coreSession);

            Throwable failure = null;
            try (AutoLock ignored = lock.lock())
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
            {
                coreSession.close(CloseStatus.SHUTDOWN, failure.getMessage(), Callback.from(callback, failure));
            }
            else
            {
                callback.succeeded();
                coreSession.demand(1);
            }
        }

        private void onConnectFailure(Throwable t, Callback callback)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("[{}] onConnectFailure", this, t);

            Throwable failure = t;
            try (AutoLock ignored = lock.lock())
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
                LOG.debug("[{}] onOpen {}", this, coreSession);

            Throwable failure = null;
            try (AutoLock ignored = lock.lock())
            {
                switch (state)
                {
                    case CONNECTING:
                        state = State.OPEN;
                        proxy2ServerSession = coreSession;
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
                LOG.debug("[{}] onFrame {}", this, frame);
            receivedFrames.offer(Frame.copy(frame));

            boolean demand = false;
            Callback sendCallback = callback;
            Throwable failure = null;
            try (AutoLock ignored = lock.lock())
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
                        else
                        {
                            demand = true;
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
            {
                callback.failed(failure);
            }
            else
            {
                if (demand)
                {
                    Callback c = sendCallback;
                    client2Proxy.send(frame, Callback.from(() ->
                    {
                        c.succeeded();
                        proxy2ServerSession.demand(1);
                    }, c::failed));
                }
                else
                {
                    client2Proxy.send(frame, sendCallback);
                }
            }
        }

        @Override
        public void onError(Throwable failure, Callback callback)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("[{}] onError", this, failure);

            boolean failClient2Proxy = false;
            try (AutoLock ignored = lock.lock())
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
                LOG.debug("[{}] onClosed {}", this, closeStatus);

            boolean abnormalClose = false;
            try (AutoLock ignored = lock.lock())
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
                LOG.debug("[{}] fail", this, failure);

            Callback sendCallback = null;
            try (AutoLock ignored = lock.lock())
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
                proxy2ServerSession.close(CloseStatus.SHUTDOWN, failure.getMessage(), sendCallback);
            else
                callback.failed(failure);
        }

        public void send(Frame frame, Callback callback)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("[{}] send {}", this, frame);

            Callback sendCallback = callback;
            Throwable failure = null;
            try (AutoLock ignored = lock.lock())
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
                proxy2ServerSession.sendFrame(frame, sendCallback, false);
        }

        @Override
        public String toString()
        {
            return "Proxy2Server:" + getState();
        }
    }
}
