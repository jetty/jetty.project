//
//  ========================================================================
//  Copyright (c) 1995-2018 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.jsr356.tests;

import java.net.SocketAddress;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.util.AttributesMap;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.websocket.core.*;
import org.eclipse.jetty.websocket.core.ExtensionConfig;

public class DummyChannel implements FrameHandler.CoreSession
{
    private final AttributesMap attributes = new AttributesMap();
    
    @Override
    public String getSubprotocol()
    {
        return null;
    }

    @Override
    public List<ExtensionConfig> getExtensionConfig()
    {
        return null;
    }

    @Override
    public void abort()
    {
    }

    @Override
    public Behavior getBehavior()
    {
        return null;
    }

    @Override
    public WebSocketPolicy getPolicy()
    {
        return null;
    }

    @Override
    public SocketAddress getLocalAddress()
    {
        return null;
    }

    @Override
    public SocketAddress getRemoteAddress()
    {
        return null;
    }

    @Override
    public boolean isOpen()
    {
        return false;
    }

    @Override
    public long getIdleTimeout(TimeUnit units)
    {
        return 0;
    }

    @Override
    public void sendFrame(Frame frame, Callback callback, BatchMode batchMode)
    {
    }

    @Override
    public void setIdleTimeout(long timeout, TimeUnit units)
    {
    }

    @Override
    public void flushBatch(Callback callback)
    {
    }

    @Override
    public void close(Callback callback)
    {
    }

    @Override
    public void close(int statusCode, String reason, Callback callback)
    {
    }

    @Override
    public void demand(long n)
    {        
    }
}
