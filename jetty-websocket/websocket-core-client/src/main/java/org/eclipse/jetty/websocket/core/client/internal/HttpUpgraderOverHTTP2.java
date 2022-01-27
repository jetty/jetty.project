//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.websocket.core.client.internal;

import org.eclipse.jetty.client.HttpRequest;
import org.eclipse.jetty.client.HttpResponse;
import org.eclipse.jetty.client.HttpUpgrader;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.PreEncodedHttpField;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.websocket.core.WebSocketConstants;
import org.eclipse.jetty.websocket.core.client.CoreClientUpgradeRequest;

public class HttpUpgraderOverHTTP2 implements HttpUpgrader
{
    private static final PreEncodedHttpField WS_VERSION_FIELD = new PreEncodedHttpField(HttpHeader.SEC_WEBSOCKET_VERSION, WebSocketConstants.SPEC_VERSION_STRING);
    private final CoreClientUpgradeRequest clientUpgradeRequest;

    public HttpUpgraderOverHTTP2(CoreClientUpgradeRequest clientUpgradeRequest)
    {
        this.clientUpgradeRequest = clientUpgradeRequest;
    }

    @Override
    public void prepare(HttpRequest request)
    {
        request.upgradeProtocol("websocket")
            .method(HttpMethod.CONNECT)
            .headers(headers -> headers.put(WS_VERSION_FIELD));

        // Notify the UpgradeListeners now the headers are set.
        clientUpgradeRequest.requestComplete();
    }

    @Override
    public void upgrade(HttpResponse response, EndPoint endPoint, Callback callback)
    {
        try
        {
            clientUpgradeRequest.upgrade(response, endPoint);
            callback.succeeded();
        }
        catch (Throwable x)
        {
            callback.failed(x);
        }
    }
}
