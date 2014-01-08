//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
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
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.client.ClientUpgradeRequest;
import org.eclipse.jetty.websocket.client.ClientUpgradeResponse;
import org.eclipse.jetty.websocket.client.WebSocketClient;
import org.eclipse.jetty.websocket.client.masks.Masker;
import org.eclipse.jetty.websocket.common.WebSocketSession;
import org.eclipse.jetty.websocket.common.events.EventDriver;

/**
 * Holder for the pending connect information.
 */
public abstract class ConnectPromise extends FuturePromise<Session> implements Runnable
{
    private final WebSocketClient client;
    private final EventDriver driver;
    private final ClientUpgradeRequest request;
    private final Masker masker;
    private UpgradeListener upgradeListener;
    private ClientUpgradeResponse response;

    public ConnectPromise(WebSocketClient client, EventDriver driver, ClientUpgradeRequest request)
    {
        this.client = client;
        this.driver = driver;
        this.request = request;
        this.masker = client.getMasker();
    }

    @Override
    public void failed(Throwable cause)
    {
        // Notify websocket of failure to connect
        driver.onError(cause);

        // Notify promise/future of failure to connect
        super.failed(cause);
    }

    public WebSocketClient getClient()
    {
        return client;
    }

    public EventDriver getDriver()
    {
        return this.driver;
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

    public void succeeded(WebSocketSession session)
    {
        session.setUpgradeRequest(request);
        session.setUpgradeResponse(response);
        session.open();
        super.succeeded(session);
    }
}
