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

package org.eclipse.jetty.websocket.javax.tests;

import java.util.Map;
import java.util.TreeMap;

import org.eclipse.jetty.http.HttpHeader;

public class UpgradeUtils
{
    public static String generateUpgradeRequest(CharSequence requestPath, Map<String, String> headers)
    {
        StringBuilder upgradeRequest = new StringBuilder();
        upgradeRequest.append("GET ");
        upgradeRequest.append(requestPath == null ? "/" : requestPath);
        upgradeRequest.append(" HTTP/1.1\r\n");
        headers.entrySet().stream().forEach(e ->
            upgradeRequest.append(e.getKey()).append(": ").append(e.getValue()).append("\r\n"));
        upgradeRequest.append("\r\n");
        return upgradeRequest.toString();
    }

    public static String generateUpgradeRequest()
    {
        return generateUpgradeRequest("/", newDefaultUpgradeRequestHeaders());
    }

    public static String generateUpgradeRequest(CharSequence requestPath)
    {
        return generateUpgradeRequest(requestPath, newDefaultUpgradeRequestHeaders());
    }

    public static Map<String, String> newDefaultUpgradeRequestHeaders()
    {
        Map<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        headers.put("Host", "local");
        headers.put("Connection", "Upgrade");
        headers.put("Upgrade", "WebSocket");
        headers.put(HttpHeader.SEC_WEBSOCKET_KEY.asString(), "dGhlIHNhbXBsZSBub25jZQ==");
        headers.put(HttpHeader.ORIGIN.asString(), "ws://local/");
        // headers.put(WSConstants.SEC_WEBSOCKET_PROTOCOL, "echo");
        headers.put(HttpHeader.SEC_WEBSOCKET_VERSION.asString(), "13");
        return headers;
    }
}
