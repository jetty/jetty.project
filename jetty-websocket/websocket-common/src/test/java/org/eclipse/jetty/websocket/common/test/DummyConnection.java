//
//  ========================================================================
//  Copyright (c) 1995-2017 Mort Bay Consulting Pty. Ltd.
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
import org.eclipse.jetty.websocket.api.BatchMode;
import org.eclipse.jetty.websocket.api.FrameCallback;
import org.eclipse.jetty.websocket.api.SuspendToken;
import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.api.extensions.Frame;
import org.eclipse.jetty.websocket.common.LogicalConnection;

public class DummyConnection implements LogicalConnection
{
    private WebSocketPolicy policy;

    @Deprecated
    public DummyConnection()
    {
        this(WebSocketPolicy.newServerPolicy());
    }

    public DummyConnection(WebSocketPolicy policy)
    {
        this.policy = policy;
    }

    @Override
    public void disconnect()
    {
    }
    
    @Override
    public void fillInterested()
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
    public boolean isOpen()
    {
        return false;
    }

    @Override
    public void outgoingFrame(Frame frame, FrameCallback callback, BatchMode batchMode)
    {
        callback.succeed();
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
    public SuspendToken suspend()
    {
        return null;
    }
}
