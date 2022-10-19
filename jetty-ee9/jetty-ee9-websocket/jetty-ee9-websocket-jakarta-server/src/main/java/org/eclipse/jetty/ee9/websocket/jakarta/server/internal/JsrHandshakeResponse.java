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

package org.eclipse.jetty.ee9.websocket.jakarta.server.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import jakarta.websocket.HandshakeResponse;
import org.eclipse.jetty.websocket.core.server.ServerUpgradeResponse;

public class JsrHandshakeResponse implements HandshakeResponse
{
    private final ServerUpgradeResponse delegate;
    private final Map<String, List<String>> headers;

    public JsrHandshakeResponse(ServerUpgradeResponse resp)
    {
        this.delegate = resp;
        this.headers = delegate.getHeaders().getFieldNamesCollection().stream()
            .collect(Collectors.toMap((name) -> name, (name) -> new ArrayList<>(delegate.getHeaders().getValuesList(name))));
    }

    @Override
    public Map<String, List<String>> getHeaders()
    {
        return headers;
    }

    public void setHeaders(Map<String, List<String>> headers)
    {
        headers.forEach((key, values) -> delegate.getHeaders().put(key, values));
    }
}
