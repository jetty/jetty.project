//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.thread.ExecutorThreadPool;
import org.eclipse.jetty.websocket.api.BatchMode;
import org.eclipse.jetty.websocket.api.StatusCode;
import org.eclipse.jetty.websocket.api.SuspendToken;
import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.api.WriteCallback;
import org.eclipse.jetty.websocket.api.extensions.Frame;
import org.eclipse.jetty.websocket.api.extensions.IncomingFrames;
import org.eclipse.jetty.websocket.common.CloseInfo;
import org.eclipse.jetty.websocket.common.ConnectionState;
import org.eclipse.jetty.websocket.common.LogicalConnection;
import org.eclipse.jetty.websocket.common.io.IOState.ConnectionStateListener;
import org.junit.rules.TestName;

public class LocalWebSocketConnection implements LogicalConnection, IncomingFrames, ConnectionStateListener
{
    private static final Logger LOG = Log.getLogger(LocalWebSocketConnection.class);
    private final String id;
    private final ByteBufferPool bufferPool;
    private final Executor executor;
    private WebSocketPolicy policy = WebSocketPolicy.newServerPolicy();
    private IncomingFrames incoming;
    private IOState ioState = new IOState();

    public LocalWebSocketConnection(ByteBufferPool bufferPool)
    {
        this("anon",bufferPool);
    }

    public LocalWebSocketConnection(String id, ByteBufferPool bufferPool)
    {
        this.id = id;
        this.bufferPool = bufferPool;
        this.executor = new ExecutorThreadPool();
        this.ioState.addListener(this);
    }

    public LocalWebSocketConnection(TestName testname, ByteBufferPool bufferPool)
    {
        this(testname.getMethodName(),bufferPool);
    }
    
    @Override
    public Executor getExecutor()
    {
        return executor;
    }

    @Override
    public void close()
    {
        close(StatusCode.NORMAL,null);
    }

    @Override
    public void close(int statusCode, String reason)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("close({}, {})",statusCode,reason);
        CloseInfo close = new CloseInfo(statusCode,reason);
        ioState.onCloseLocal(close);
    }

    public void connect()
    {
        if (LOG.isDebugEnabled())
            LOG.debug("connect()");
        ioState.onConnected();
    }

    @Override
    public void disconnect()
    {
        if (LOG.isDebugEnabled())
            LOG.debug("disconnect()");
    }

    @Override
    public ByteBufferPool getBufferPool()
    {
        return this.bufferPool;
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
    public IOState getIOState()
    {
        return ioState;
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
    public WebSocketPolicy getPolicy()
    {
        return policy;
    }

    @Override
    public InetSocketAddress getRemoteAddress()
    {
        return null;
    }

    @Override
    public void incomingError(Throwable e)
    {
        incoming.incomingError(e);
    }

    @Override
    public void incomingFrame(Frame frame)
    {
        incoming.incomingFrame(frame);
    }

    @Override
    public boolean isOpen()
    {
        return getIOState().isOpen();
    }

    @Override
    public boolean isReading()
    {
        return false;
    }

    @Override
    public void onConnectionStateChange(ConnectionState state)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("Connection State Change: {}",state);
        switch (state)
        {
            case CLOSED:
                this.disconnect();
                break;
            case CLOSING:
                if (ioState.wasRemoteCloseInitiated())
                {
                    // send response close frame
                    CloseInfo close = ioState.getCloseInfo();
                    LOG.debug("write close frame: {}",close);
                    ioState.onCloseLocal(close);
                }
            default:
                break;
        }
    }

    public void open()
    {
        if (LOG.isDebugEnabled())
            LOG.debug("open()");
        ioState.onOpened();
    }

    @Override
    public void outgoingFrame(Frame frame, WriteCallback callback, BatchMode batchMode)
    {
    }

    @Override
    public void resume()
    {
    }

    @Override
    public void setMaxIdleTimeout(long ms)
    {
    }

    @Override
    public void setNextIncomingFrames(IncomingFrames incoming)
    {
        this.incoming = incoming;
    }

    public void setPolicy(WebSocketPolicy policy)
    {
        this.policy = policy;
    }

    @Override
    public SuspendToken suspend()
    {
        return null;
    }

    @Override
    public String toString()
    {
        return String.format("%s[%s]",LocalWebSocketConnection.class.getSimpleName(),id);
    }
}
