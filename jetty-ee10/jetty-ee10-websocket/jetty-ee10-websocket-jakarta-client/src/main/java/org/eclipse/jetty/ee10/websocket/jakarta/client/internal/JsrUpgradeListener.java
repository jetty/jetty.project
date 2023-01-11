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

package org.eclipse.jetty.ee10.websocket.jakarta.client.internal;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import jakarta.websocket.ClientEndpointConfig.Configurator;
import jakarta.websocket.HandshakeResponse;
import org.eclipse.jetty.client.Request;
import org.eclipse.jetty.client.Response;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.websocket.core.client.UpgradeListener;

public class JsrUpgradeListener implements UpgradeListener
{
    private final Configurator configurator;

    public JsrUpgradeListener(Configurator configurator)
    {
        this.configurator = configurator;
    }

    @Override
    public void onHandshakeRequest(Request request)
    {
        if (configurator == null)
            return;

        HttpFields fields = request.getHeaders();
        Map<String, List<String>> originalHeaders = new HashMap<>();
        fields.forEach(field ->
        {
            originalHeaders.putIfAbsent(field.getName(), new ArrayList<>());
            List<String> values = originalHeaders.get(field.getName());
            Collections.addAll(values, field.getValues());
        });

        // Give headers to configurator
        configurator.beforeRequest(originalHeaders);

        // Reset headers on HttpRequest per configurator
        request.headers(headers ->
        {
            headers.clear();
            originalHeaders.forEach(headers::put);
        });
    }

    @Override
    public void onHandshakeResponse(Request request, Response response)
    {
        if (configurator == null)
            return;

        HandshakeResponse handshakeResponse = () ->
        {
            Map<String, List<String>> ret = new HashMap<>();
            response.getHeaders().forEach(field ->
            {
                ret.putIfAbsent(field.getName(), new ArrayList<>());
                List<String> values = ret.get(field.getName());
                Collections.addAll(values, field.getValues());
            });
            return ret;
        };

        configurator.afterResponse(handshakeResponse);
    }
}
