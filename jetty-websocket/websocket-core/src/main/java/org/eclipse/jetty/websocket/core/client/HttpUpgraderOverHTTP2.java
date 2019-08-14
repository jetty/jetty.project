//
//  ========================================================================
//  Copyright (c) 1995-2019 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.core.client;

import org.eclipse.jetty.client.HttpRequest;
import org.eclipse.jetty.client.HttpResponse;
import org.eclipse.jetty.client.HttpUpgrader;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.websocket.core.WebSocketConstants;

public class HttpUpgraderOverHTTP2 implements HttpUpgrader
{
    private final ClientUpgradeRequest clientUpgradeRequest;

    public HttpUpgraderOverHTTP2(ClientUpgradeRequest clientUpgradeRequest)
    {
        this.clientUpgradeRequest = clientUpgradeRequest;
    }

    @Override
    public void prepare(HttpRequest request)
    {
        request.method(HttpMethod.CONNECT);
        request.upgradeProtocol("websocket");
        request.header(HttpHeader.SEC_WEBSOCKET_VERSION, WebSocketConstants.SPEC_VERSION_STRING);
    }

    @Override
    public void upgrade(HttpResponse response, EndPoint endPoint)
    {
        clientUpgradeRequest.upgrade(response, endPoint);
    }
}
