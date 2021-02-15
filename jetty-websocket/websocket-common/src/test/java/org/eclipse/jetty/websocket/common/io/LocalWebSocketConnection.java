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

package org.eclipse.jetty.websocket.common.io;

import java.net.InetSocketAddress;
import java.util.concurrent.Executor;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.thread.ExecutorThreadPool;
import org.eclipse.jetty.websocket.api.BatchMode;
import org.eclipse.jetty.websocket.api.CloseException;
import org.eclipse.jetty.websocket.api.StatusCode;
import org.eclipse.jetty.websocket.api.SuspendToken;
import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.api.WriteCallback;
import org.eclipse.jetty.websocket.api.extensions.Frame;
import org.eclipse.jetty.websocket.api.extensions.IncomingFrames;
import org.eclipse.jetty.websocket.common.CloseInfo;
import org.eclipse.jetty.websocket.common.LogicalConnection;
import org.eclipse.jetty.websocket.common.WebSocketSession;

public class LocalWebSocketConnection implements LogicalConnection, IncomingFrames
{
    private static final Logger LOG = Log.getLogger(LocalWebSocketConnection.class);
    private final String id;
    private final ByteBufferPool bufferPool;
    private final Executor executor;
    private final ConnectionState connectionState = new ConnectionState();
    private WebSocketSession session;
    private WebSocketPolicy policy = WebSocketPolicy.newServerPolicy();
    private IncomingFrames incoming;

    public LocalWebSocketConnection(ByteBufferPool bufferPool)
    {
        this("anon", bufferPool);
    }

    public LocalWebSocketConnection(String id, ByteBufferPool bufferPool)
    {
        this.id = id;
        this.bufferPool = bufferPool;
        this.executor = new ExecutorThreadPool();
    }

    @Override
    public boolean canReadWebSocketFrames()
    {
        return connectionState.canReadWebSocketFrames();
    }

    @Override
    public boolean canWriteWebSocketFrames()
    {
        return connectionState.canWriteWebSocketFrames();
    }

    @Override
    public void close(Throwable cause)
    {
        Callback callback = Callback.NOOP;
        if (cause instanceof CloseException)
        {
            callback = new DisconnectCallback();
        }
        close(cause, callback);
    }

    @Override
    public void close(CloseInfo close, Callback callback)
    {
        if (connectionState.closing())
        {
            // pretend we sent the close frame and the remote responded
            session.callApplicationOnClose(close);
            disconnect();
        }
        else
        {
            if (callback != null)
            {
                callback.failed(new IllegalStateException("Local Close already called"));
            }
        }
    }

    @Override
    public void disconnect()
    {
        connectionState.disconnected();
    }

    @Override
    public ByteBufferPool getBufferPool()
    {
        return this.bufferPool;
    }

    @Override
    public Executor getExecutor()
    {
        return executor;
    }

    @Override
    public String getId()
    {
        return this.id;
    }

    @Override
    public long getIdleTimeout()
    {
        return 0;
    }

    public IncomingFrames getIncoming()
    {
        return incoming;
    }

    @Override
    public InetSocketAddress getLocalAddress()
    {
        return null;
    }

    @Override
    public long getMaxIdleTimeout()
    {
        return 0;
    }

    @Override
    public void setMaxIdleTimeout(long ms)
    {
    }

    @Override
    public WebSocketPolicy getPolicy()
    {
        return policy;
    }

    public void setPolicy(WebSocketPolicy policy)
    {
        this.policy = policy;
    }

    @Override
    public InetSocketAddress getRemoteAddress()
    {
        return null;
    }

    @Override
    public void incomingFrame(Frame frame)
    {
        incoming.incomingFrame(frame);
    }

    @Override
    public boolean isOpen()
    {
        return true;
    }

    @Override
    public boolean isReading()
    {
        return false;
    }

    @Override
    public boolean opened()
    {
        return connectionState.opened();
    }

    @Override
    public boolean opening()
    {
        return connectionState.opening();
    }

    @Override
    public void outgoingFrame(Frame frame, WriteCallback callback, BatchMode batchMode)
    {
    }

    @Override
    public void remoteClose(CloseInfo close)
    {
        close(close, Callback.NOOP);
    }

    @Override
    public void resume()
    {
    }

    @Override
    public void setNextIncomingFrames(IncomingFrames incoming)
    {
        this.incoming = incoming;
    }

    @Override
    public void setSession(WebSocketSession session)
    {
        this.session = session;
    }

    @Override
    public SuspendToken suspend()
    {
        return null;
    }

    @Override
    public String toStateString()
    {
        return connectionState.toString();
    }

    @Override
    public String toString()
    {
        return String.format("%s[%s]", LocalWebSocketConnection.class.getSimpleName(), id);
    }

    private void close(Throwable cause, Callback callback)
    {
        session.callApplicationOnError(cause);
        close(new CloseInfo(StatusCode.SERVER_ERROR, cause.getMessage()), callback);
    }

    private class DisconnectCallback implements Callback
    {
        @Override
        public void failed(Throwable x)
        {
            disconnect();
        }

        @Override
        public void succeeded()
        {
            disconnect();
        }
    }

    private class CallbackBridge implements WriteCallback
    {
        final Callback callback;

        public CallbackBridge(Callback callback)
        {
            this.callback = callback;
        }

        @Override
        public void writeFailed(Throwable x)
        {
            callback.failed(x);
        }

        @Override
        public void writeSuccess()
        {
            callback.succeeded();
        }
    }
}
