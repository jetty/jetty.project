//
//  ========================================================================
//  Copyright (c) 1995-2012 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.client;

import java.util.Set;

import javax.net.websocket.ClientContainer;
import javax.net.websocket.ClientEndpointConfiguration;
import javax.net.websocket.DeploymentException;
import javax.net.websocket.Endpoint;
import javax.net.websocket.Session;

public class JettyClientContainer implements ClientContainer
{
    @Override
    public void connectToServer(Endpoint endpoint, ClientEndpointConfiguration olc) throws DeploymentException
    {
        // TODO Auto-generated method stub

    }

    @Override
    public Set<Session> getActiveSessions()
    {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public Set<String> getInstalledExtensions()
    {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public long getMaxBinaryMessageBufferSize()
    {
        // TODO Auto-generated method stub
        return 0;
    }

    @Override
    public long getMaxSessionIdleTimeout()
    {
        // TODO Auto-generated method stub
        return 0;
    }

    @Override
    public long getMaxTextMessageBufferSize()
    {
        // TODO Auto-generated method stub
        return 0;
    }

    @Override
    public void setMaxBinaryMessageBufferSize(long max)
    {
        // TODO Auto-generated method stub

    }

    @Override
    public void setMaxSessionIdleTimeout(long timeout)
    {
        // TODO Auto-generated method stub

    }

    @Override
    public void setMaxTextMessageBufferSize(long max)
    {
        // TODO Auto-generated method stub

    }
}
