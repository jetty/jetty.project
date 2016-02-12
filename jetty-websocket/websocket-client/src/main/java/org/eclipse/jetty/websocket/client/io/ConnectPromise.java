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

package org.eclipse.jetty.websocket.client.io;

import org.eclipse.jetty.util.FuturePromise;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.client.ClientUpgradeRequest;
import org.eclipse.jetty.websocket.client.ClientUpgradeResponse;
import org.eclipse.jetty.websocket.client.WebSocketClient;
import org.eclipse.jetty.websocket.client.masks.Masker;
import org.eclipse.jetty.websocket.common.WebSocketSession;

/**
 * Holder for the pending connect information.
 */
public abstract class ConnectPromise extends FuturePromise<Session> implements Runnable
{
    private static final Logger LOG = Log.getLogger(ConnectPromise.class);
    private final WebSocketClient client;
    private final ClientUpgradeRequest request;
    private final Object webSocketEndpoint;
    private final Masker masker;
    private UpgradeListener upgradeListener;
    private ClientUpgradeResponse response;
    private WebSocketSession session;

    public ConnectPromise(WebSocketClient client, ClientUpgradeRequest request, Object websocket)
    {
        this.client = client;
        this.request = request;
        this.webSocketEndpoint = websocket;
        this.masker = client.getMasker();
    }

    @Override
    public void failed(Throwable cause)
    {
        if (session != null)
        {
            // Notify websocket of failure to connect
            session.notifyError(cause);
        }

        // Notify promise/future of failure to connect
        super.failed(cause);
    }

    public WebSocketClient getClient()
    {
        return client;
    }
    
    public Object getWebSocketEndpoint()
    {
        return webSocketEndpoint;
    }

    public Masker getMasker()
    {
        return masker;
    }

    public ClientUpgradeRequest getRequest()
    {
        return this.request;
    }

    public ClientUpgradeResponse getResponse()
    {
        return response;
    }

    public UpgradeListener getUpgradeListener()
    {
        return upgradeListener;
    }

    public void setResponse(ClientUpgradeResponse response)
    {
        this.response = response;
    }

    public void setUpgradeListener(UpgradeListener upgradeListener)
    {
        this.upgradeListener = upgradeListener;
    }

    public void succeeded()
    {
        if(LOG.isDebugEnabled())
            LOG.debug("{}.succeeded()",this.getClass().getSimpleName());
        session.setUpgradeRequest(request);
        session.setUpgradeResponse(response);
        // session.open();
        super.succeeded(session);
    }

    public void setSession(WebSocketSession session)
    {
        this.session = session;
    }
}
