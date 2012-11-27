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

import java.io.IOException;
import java.net.URI;
import java.util.concurrent.Future;

import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.client.masks.Masker;
import org.eclipse.jetty.websocket.common.events.EventDriver;

public interface WebSocketClient
{
    public Future<ClientUpgradeResponse> connect(URI websocketUri) throws IOException;

    public WebSocketClientFactory getFactory();

    public Masker getMasker();

    public WebSocketPolicy getPolicy();

    public ClientUpgradeRequest getUpgradeRequest();

    public ClientUpgradeResponse getUpgradeResponse();

    public EventDriver getWebSocket();

    public URI getWebSocketUri();

    public void setMasker(Masker masker);
}