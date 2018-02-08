//
//  ========================================================================
//  Copyright (c) 1995-2018 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.jsr356.server;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.websocket.HandshakeResponse;

import org.eclipse.jetty.websocket.servlet.ServletUpgradeResponse;

public class JsrHandshakeResponse implements HandshakeResponse
{
    private final ServletUpgradeResponse delegate;
    private Map<String, List<String>> headerMap;

    public JsrHandshakeResponse(ServletUpgradeResponse resp)
    {
        this.delegate = resp;
        this.headerMap = new HashMap<>();
        this.headerMap.putAll(resp.getHeadersMap());
    }

    @Override
    public Map<String, List<String>> getHeaders()
    {
        return headerMap;
    }

    public void setHeaders(Map<String, List<String>> headers)
    {
        headers.forEach((key, values) -> delegate.setHeader(key, values));
    }
}
