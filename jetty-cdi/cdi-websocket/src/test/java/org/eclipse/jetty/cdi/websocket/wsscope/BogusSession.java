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

package org.eclipse.jetty.cdi.websocket.wsscope;

import java.io.IOException;
import java.net.InetSocketAddress;

import org.eclipse.jetty.websocket.api.CloseStatus;
import org.eclipse.jetty.websocket.api.RemoteEndpoint;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.SuspendToken;
import org.eclipse.jetty.websocket.api.UpgradeRequest;
import org.eclipse.jetty.websocket.api.UpgradeResponse;
import org.eclipse.jetty.websocket.api.WebSocketPolicy;

/**
 * A bogus websocket Session concept object.
 * <p>
 * Used to test the scope @Inject of this kind of Session. This is important to test, as the BogusSession does not have
 * a default constructor that CDI itself can use to create this object.
 * <p>
 * This object would need to be added to the beanstore for this scope for later @Inject to use.
 */
public class BogusSession implements Session
{
    private final String id;

    public BogusSession(String id)
    {
        this.id = id;
    }
    
    @Override
    public String toString()
    {
        return String.format("BogusSession[id=%s]",id);
    }

    public String getId()
    {
        return id;
    }

    @Override
    public void close()
    {
    }

    @Override
    public void close(CloseStatus closeStatus)
    {
    }

    @Override
    public void close(int statusCode, String reason)
    {
    }

    @Override
    public void disconnect() throws IOException
    {
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
    public WebSocketPolicy getPolicy()
    {
        return null;
    }

    @Override
    public String getProtocolVersion()
    {
        return null;
    }

    @Override
    public RemoteEndpoint getRemote()
    {
        return null;
    }

    @Override
    public InetSocketAddress getRemoteAddress()
    {
        return null;
    }

    @Override
    public UpgradeRequest getUpgradeRequest()
    {
        return null;
    }

    @Override
    public UpgradeResponse getUpgradeResponse()
    {
        return null;
    }

    @Override
    public boolean isOpen()
    {
        return false;
    }

    @Override
    public boolean isSecure()
    {
        return false;
    }

    @Override
    public void setIdleTimeout(long ms)
    {
    }

    @Override
    public SuspendToken suspend()
    {
        return null;
    }
}
