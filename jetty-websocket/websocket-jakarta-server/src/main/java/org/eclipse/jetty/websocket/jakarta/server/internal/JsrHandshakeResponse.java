//
// ========================================================================
// Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under
// the terms of the Eclipse Public License 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0
//
// This Source Code may also be made available under the following
// Secondary Licenses when the conditions for such availability set
// forth in the Eclipse Public License, v. 2.0 are satisfied:
// the Apache License v2.0 which is available at
// https://www.apache.org/licenses/LICENSE-2.0
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.websocket.jakarta.server.internal;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import jakarta.websocket.HandshakeResponse;
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
