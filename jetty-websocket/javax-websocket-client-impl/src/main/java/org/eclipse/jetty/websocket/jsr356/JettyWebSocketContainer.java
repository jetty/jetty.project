//
//  ========================================================================
//  Copyright (c) 1995-2013 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.jsr356;

import java.io.IOException;
import java.net.URI;
import java.util.Set;

import javax.websocket.ClientEndpointConfig;
import javax.websocket.DeploymentException;
import javax.websocket.Endpoint;
import javax.websocket.Extension;
import javax.websocket.Session;
import javax.websocket.WebSocketContainer;

public class JettyWebSocketContainer implements WebSocketContainer
{
    @Override
    public Session connectToServer(Class<? extends Endpoint> endpointClass, ClientEndpointConfig cec, URI path) throws DeploymentException, IOException
    {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public Session connectToServer(Class<?> annotatedEndpointClass, URI path) throws DeploymentException
    {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public Session connectToServer(Endpoint endpointInstance, ClientEndpointConfig cec, URI path) throws DeploymentException, IOException
    {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public Session connectToServer(Object annotatedEndpointInstance, URI path) throws DeploymentException, IOException
    {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public long getDefaultAsyncSendTimeout()
    {
        // TODO Auto-generated method stub
        return 0;
    }

    @Override
    public int getDefaultMaxBinaryMessageBufferSize()
    {
        // TODO Auto-generated method stub
        return 0;
    }

    @Override
    public long getDefaultMaxSessionIdleTimeout()
    {
        // TODO Auto-generated method stub
        return 0;
    }

    @Override
    public int getDefaultMaxTextMessageBufferSize()
    {
        // TODO Auto-generated method stub
        return 0;
    }

    @Override
    public Set<Extension> getInstalledExtensions()
    {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public void setAsyncSendTimeout(long timeoutmillis)
    {
        // TODO Auto-generated method stub

    }

    @Override
    public void setDefaultMaxBinaryMessageBufferSize(int max)
    {
        // TODO Auto-generated method stub

    }

    @Override
    public void setDefaultMaxSessionIdleTimeout(long timeout)
    {
        // TODO Auto-generated method stub

    }

    @Override
    public void setDefaultMaxTextMessageBufferSize(int max)
    {
        // TODO Auto-generated method stub

    }
}
