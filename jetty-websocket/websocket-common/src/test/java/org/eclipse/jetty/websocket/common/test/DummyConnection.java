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

package org.eclipse.jetty.websocket.common.test;

import java.net.InetSocketAddress;
import java.util.concurrent.Executor;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.api.BatchMode;
import org.eclipse.jetty.websocket.api.SuspendToken;
import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.api.WriteCallback;
import org.eclipse.jetty.websocket.api.extensions.Frame;
import org.eclipse.jetty.websocket.api.extensions.IncomingFrames;
import org.eclipse.jetty.websocket.common.CloseInfo;
import org.eclipse.jetty.websocket.common.LogicalConnection;
import org.eclipse.jetty.websocket.common.WebSocketSession;

public class DummyConnection implements LogicalConnection
{
    private static final Logger LOG = Log.getLogger(DummyConnection.class);

    public DummyConnection()
    {
    }

    @Override
    public boolean canReadWebSocketFrames()
    {
        return true;
    }

    @Override
    public boolean canWriteWebSocketFrames()
    {
        return true;
    }

    @Override
    public void close(Throwable cause)
    {
        LOG.warn(cause);
    }

    @Override
    public void close(CloseInfo closeInfo, Callback callback)
    {
    }

    @Override
    public void disconnect()
    {
    }

    @Override
    public ByteBufferPool getBufferPool()
    {
        return null;
    }

    @Override
    public Executor getExecutor()
    {
        return null;
    }

    @Override
    public String getId()
    {
        return "dummy";
    }

    @Override
    public long getIdleTimeout()
    {
        return 0;
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
        return null;
    }

    @Override
    public InetSocketAddress getRemoteAddress()
    {
        return null;
    }

    @Override
    public boolean isOpen()
    {
        return false;
    }

    @Override
    public boolean isReading()
    {
        return false;
    }

    @Override
    public boolean opened()
    {
        return false;
    }

    @Override
    public boolean opening()
    {
        return false;
    }

    @Override
    public void outgoingFrame(Frame frame, WriteCallback callback, BatchMode batchMode)
    {
        callback.writeSuccess();
    }

    @Override
    public void remoteClose(CloseInfo close)
    {

    }

    @Override
    public void resume()
    {
    }

    @Override
    public void setNextIncomingFrames(IncomingFrames incoming)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("setNextIncomingFrames({})", incoming);
    }

    @Override
    public void setSession(WebSocketSession session)
    {
    }

    @Override
    public SuspendToken suspend()
    {
        return null;
    }

    @Override
    public String toStateString()
    {
        return "no-state-in-dummy";
    }
}
