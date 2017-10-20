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

import org.eclipse.jetty.io.ByteArrayEndPoint;
import org.eclipse.jetty.io.MappedByteBufferPool;
import org.eclipse.jetty.util.DecoratedObjectFactory;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.thread.ExecutorThreadPool;
import org.eclipse.jetty.websocket.common.UpgradeRequestAdapter;
import org.eclipse.jetty.websocket.common.UpgradeResponseAdapter;
import org.eclipse.jetty.websocket.core.IncomingFrames;
import org.eclipse.jetty.websocket.core.OutgoingFrames;
import org.eclipse.jetty.websocket.core.WebSocketPolicy;
import org.eclipse.jetty.websocket.core.extensions.ExtensionStack;
import org.eclipse.jetty.websocket.core.extensions.WebSocketExtensionRegistry;
import org.eclipse.jetty.websocket.core.io.WebSocketCoreConnection;
import org.junit.rules.TestName;

public class LocalWebSocketConnection extends WebSocketCoreConnection
{
    private static final Logger LOG = Log.getLogger(LocalWebSocketConnection.class);
    private final String id;

    public LocalWebSocketConnection(String id)
    {
        this(id, WebSocketPolicy.newServerPolicy());
    }

    public LocalWebSocketConnection(TestName testname, WebSocketPolicy policy)
    {
        this(testname.getMethodName(), policy);
    }

    public LocalWebSocketConnection(String id, WebSocketPolicy policy)
    {
        super(
                new ByteArrayEndPoint(),
                new ExecutorThreadPool(),
                new MappedByteBufferPool(),
                new DecoratedObjectFactory(),
                policy,
                new ExtensionStack(new WebSocketExtensionRegistry()),
                new UpgradeRequestAdapter(URI.create("ws://local/" + LocalWebSocketConnection.class.getSimpleName() + "/" + id)),
                new UpgradeResponseAdapter()
        );
        this.id = id;

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
    public String getId()
    {
        return this.id;
    }

    @Override
    public long getIdleTimeout()
    {
        return 0;
    }

    public IncomingFrames getIncomingFrames()
    {
        return super.incomingFrames;
    }

    public OutgoingFrames getOutgoingFrames()
    {
        return super.outgoingFrames;
    }

    @Override
    public InetSocketAddress getLocalAddress()
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
    public void resume()
    {
    }

    public void setIncomingFrames(IncomingFrames incomingFrames)
    {
        super.incomingFrames = incomingFrames;
    }

    public void setOutgoingFrames(OutgoingFrames outgoingFrames)
    {
        super.outgoingFrames = outgoingFrames;
    }

    @Override
    public void setMaxIdleTimeout(long ms)
    {
    }

    @Override
    public String toConnectionString()
    {
        return String.format("%s[%s]",LocalWebSocketConnection.class.getSimpleName(),id);
    }
}
