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

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.ThreadLocalRandom;

import org.eclipse.jetty.client.HttpRequest;
import org.eclipse.jetty.client.HttpResponse;
import org.eclipse.jetty.client.HttpResponseException;
import org.eclipse.jetty.client.HttpUpgrader;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.websocket.core.WebSocketConstants;
import org.eclipse.jetty.websocket.core.internal.WebSocketCore;

public class HttpUpgraderOverHTTP implements HttpUpgrader
{
    private final ClientUpgradeRequest clientUpgradeRequest;

    public HttpUpgraderOverHTTP(ClientUpgradeRequest clientUpgradeRequest)
    {
        this.clientUpgradeRequest = clientUpgradeRequest;
    }

    @Override
    public void prepare(HttpRequest request)
    {
        request.method(HttpMethod.GET).version(HttpVersion.HTTP_1_1);
        request.header(HttpHeader.SEC_WEBSOCKET_VERSION, WebSocketConstants.SPEC_VERSION_STRING);
        request.header(HttpHeader.UPGRADE, "websocket");
        request.header(HttpHeader.CONNECTION, "Upgrade");
        request.header(HttpHeader.SEC_WEBSOCKET_KEY, generateRandomKey());
        // Per the hybi list: Add no-cache headers to avoid compatibility issue.
        // There are some proxies that rewrite "Connection: upgrade" to
        // "Connection: close" in the response if a request doesn't contain
        // these headers.
        request.header(HttpHeader.PRAGMA, "no-cache");
        request.header(HttpHeader.CACHE_CONTROL, "no-cache");
    }

    private String generateRandomKey()
    {
        byte[] bytes = new byte[16];
        ThreadLocalRandom.current().nextBytes(bytes);
        return new String(Base64.getEncoder().encode(bytes), StandardCharsets.US_ASCII);
    }

    @Override
    public void upgrade(HttpResponse response, EndPoint endPoint)
    {
        HttpRequest request = (HttpRequest)response.getRequest();
        HttpFields requestHeaders = request.getHeaders();
        if (!requestHeaders.get(HttpHeader.UPGRADE).equalsIgnoreCase("websocket"))
            throw new HttpResponseException("Not a WebSocket Upgrade", response);

        // Check the Accept hash
        String reqKey = requestHeaders.get(HttpHeader.SEC_WEBSOCKET_KEY);
        String expectedHash = WebSocketCore.hashKey(reqKey);
        String respHash = response.getHeaders().get(HttpHeader.SEC_WEBSOCKET_ACCEPT);
        if (!expectedHash.equalsIgnoreCase(respHash))
            throw new HttpResponseException("Invalid Sec-WebSocket-Accept hash (was:" + respHash + ", expected:" + expectedHash + ")", response);

        clientUpgradeRequest.upgrade(response, endPoint);
    }
}
