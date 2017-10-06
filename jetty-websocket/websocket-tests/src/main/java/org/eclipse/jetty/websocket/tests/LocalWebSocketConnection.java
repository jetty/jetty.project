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

package org.eclipse.jetty.websocket.tests;

import java.net.InetSocketAddress;
import java.net.URI;
import java.util.concurrent.Executor;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.thread.ExecutorSizedThreadPool;
import org.eclipse.jetty.websocket.api.BatchMode;
import org.eclipse.jetty.websocket.api.FrameCallback;
import org.eclipse.jetty.websocket.api.SuspendToken;
import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.api.extensions.Frame;
import org.eclipse.jetty.websocket.common.LogicalConnection;
import org.eclipse.jetty.websocket.common.scopes.WebSocketContainerScope;
import org.junit.rules.TestName;

public class LocalWebSocketConnection implements LogicalConnection
{
    private static final Logger LOG = Log.getLogger(LocalWebSocketConnection.class);
    private final String id;
    private final ByteBufferPool bufferPool;
    private final Executor executor;
    private WebSocketPolicy policy;

    public LocalWebSocketConnection(ByteBufferPool bufferPool)
    {
        this("anon",bufferPool);
    }
    
    public LocalWebSocketConnection(URI uri, ByteBufferPool bufferPool)
    {
        this(uri.toASCIIString(), bufferPool);
    }

    public LocalWebSocketConnection(String id, ByteBufferPool bufferPool)
    {
        this.id = id;
        this.bufferPool = bufferPool;
        this.executor = new ExecutorSizedThreadPool();
        this.policy = WebSocketPolicy.newServerPolicy();
    }

    public LocalWebSocketConnection(TestName testname, WebSocketContainerScope containerScope)
    {
        this(testname.getMethodName(), containerScope.getBufferPool());
        this.policy = containerScope.getPolicy();
    }
    
    @Override
    public Executor getExecutor()
    {
        return executor;
    }

    @Override
    public void disconnect()
    {
        if (LOG.isDebugEnabled())
            LOG.debug("disconnect()");
    }
    
    @Override
    public void fillInterested()
    {
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
    }

    @Override
    public void resume()
    {
    }

    @Override
    public void setMaxIdleTimeout(long ms)
    {
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
