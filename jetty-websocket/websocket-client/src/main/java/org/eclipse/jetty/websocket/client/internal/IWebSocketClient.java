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

package org.eclipse.jetty.websocket.client.internal;

import java.io.IOException;
import java.net.URI;

import org.eclipse.jetty.util.FutureCallback;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.api.UpgradeRequest;
import org.eclipse.jetty.websocket.api.UpgradeResponse;
import org.eclipse.jetty.websocket.api.WebSocketConnection;
import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.client.WebSocketClient;
import org.eclipse.jetty.websocket.client.WebSocketClientFactory;
import org.eclipse.jetty.websocket.driver.WebSocketEventDriver;

/**
 * WebSocketClient for working with Upgrade (request and response), and establishing connections to the websocket URI of your choice.
 */
public class IWebSocketClient extends FutureCallback<UpgradeResponse> implements WebSocketClient
{
    private static final Logger LOG = Log.getLogger(IWebSocketClient.class);

    private final WebSocketClientFactory factory;
    private final WebSocketPolicy policy;
    private final WebSocketEventDriver websocket;
    private URI websocketUri;
    /**
     * The abstract WebSocketConnection in use and established for this client.
     * <p>
     * Note: this is intentionally kept neutral, as WebSocketClient must be able to handle connections that are either physical (a socket connection) or virtual
     * (eg: a mux connection).
     */
    private WebSocketConnection connection;
    private ClientUpgradeRequest upgradeRequest;
    private ClientUpgradeResponse upgradeResponse;

    public IWebSocketClient(WebSocketClientFactory factory, WebSocketEventDriver websocket)
    {
        this.factory = factory;
        this.policy = factory.getPolicy();
        this.websocket = websocket;
        this.upgradeRequest = new ClientUpgradeRequest();
    }

    @Override
    public void completed(UpgradeResponse context)
    {
        LOG.debug("completed() - {}",context);
        // TODO Auto-generated method stub
        super.completed(context);
    }

    /*
     * (non-Javadoc)
     * 
     * @see org.eclipse.jetty.websocket.client.internal.WebSocketClient#connect(java.net.URI)
     */
    @Override
    public FutureCallback<UpgradeResponse> connect(URI websocketUri) throws IOException
    {
        if (!factory.isStarted())
        {
            throw new IllegalStateException(WebSocketClientFactory.class.getSimpleName() + " is not started");
        }

        // Validate websocket URI
        if (!websocketUri.isAbsolute())
        {
            throw new IllegalArgumentException("WebSocket URI must be absolute");
        }

        if (StringUtil.isBlank(websocketUri.getScheme()))
        {
            throw new IllegalArgumentException("WebSocket URI must include a scheme");
        }

        String scheme = websocketUri.getScheme().toLowerCase();
        if (("ws".equals(scheme) == false) && ("wss".equals(scheme) == false))
        {
            throw new IllegalArgumentException("WebSocket URI scheme only supports [ws] and [wss], not [" + scheme + "]");
        }

        this.websocketUri = websocketUri;

        // Validate websocket URI
        FutureCallback<UpgradeResponse> result = null;

        LOG.debug("connect({})",websocketUri);

        ConnectionManager manager = factory.getConnectionManager();
        // Check with factory first for possible alternate connect mechanism (such as mux)
        result = manager.connectVirtual(this);
        if (result == null)
        {
            // No such connect option, attempt to use a physical connection
            result = manager.connectPhysical(this);
        }

        return result;
    }

    @Override
    public void failed(UpgradeResponse context, Throwable cause)
    {
        LOG.debug("failed() - {}, {}",context,cause);
        LOG.info(cause);
        // TODO Auto-generated method stub
        super.failed(context,cause);
    }

    protected ClientUpgradeRequest getClientUpgradeRequest()
    {
        return upgradeRequest;
    }

    /*
     * (non-Javadoc)
     * 
     * @see org.eclipse.jetty.websocket.client.internal.WebSocketClient#getConnection()
     */
    @Override
    public WebSocketConnection getConnection()
    {
        return this.connection;
    }

    /*
     * (non-Javadoc)
     * 
     * @see org.eclipse.jetty.websocket.client.internal.WebSocketClient#getFactory()
     */
    @Override
    public WebSocketClientFactory getFactory()
    {
        return factory;
    }

    /*
     * (non-Javadoc)
     * 
     * @see org.eclipse.jetty.websocket.client.internal.WebSocketClient#getPolicy()
     */
    @Override
    public WebSocketPolicy getPolicy()
    {
        return this.policy;
    }

    /*
     * (non-Javadoc)
     * 
     * @see org.eclipse.jetty.websocket.client.internal.WebSocketClient#getUpgradeRequest()
     */
    @Override
    public UpgradeRequest getUpgradeRequest()
    {
        return upgradeRequest;
    }

    /*
     * (non-Javadoc)
     * 
     * @see org.eclipse.jetty.websocket.client.internal.WebSocketClient#getUpgradeResponse()
     */
    @Override
    public UpgradeResponse getUpgradeResponse()
    {
        return upgradeResponse;
    }

    /*
     * (non-Javadoc)
     * 
     * @see org.eclipse.jetty.websocket.client.internal.WebSocketClient#getWebSocket()
     */
    @Override
    public WebSocketEventDriver getWebSocket()
    {
        return websocket;
    }

    /*
     * (non-Javadoc)
     * 
     * @see org.eclipse.jetty.websocket.client.internal.WebSocketClient#getWebSocketUri()
     */
    @Override
    public URI getWebSocketUri()
    {
        return websocketUri;
    }

    public void setUpgradeResponse(UpgradeResponse response)
    {
        // TODO Auto-generated method stub

    }
}
