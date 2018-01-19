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

package org.eclipse.jetty.websocket.jsr356.client;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import javax.websocket.ClientEndpointConfig.Configurator;
import javax.websocket.HandshakeResponse;

import org.eclipse.jetty.client.HttpRequest;
import org.eclipse.jetty.client.HttpResponse;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.websocket.core.client.UpgradeListener;

public class JsrUpgradeListener implements UpgradeListener
{
    private Configurator configurator;

    public JsrUpgradeListener(Configurator configurator)
    {
        this.configurator = configurator;
    }

    @Override
    public void onHandshakeRequest(HttpRequest request)
    {
        if (configurator == null)
        {
            return;
        }

        HttpFields fields = request.getHeaders();

        Map<String, List<String>> originalHeaders = new HashMap<>();
        fields.forEach((field) ->
        {
            List<String> values = new ArrayList<>();
            Stream.of(field.getValues()).forEach((val) -> values.add(val));
            originalHeaders.put(field.getName(), values);
        });

        // Give headers to configurator
        configurator.beforeRequest(originalHeaders);

        // Reset headers on HttpRequest per configurator
        fields.clear();
        originalHeaders.forEach((name, values) -> fields.put(name, values));
    }

    @Override
    public void onHandshakeResponse(HttpRequest request, HttpResponse response)
    {
        if (configurator == null)
        {
            return;
        }

        HandshakeResponse handshakeResponse = () -> {
            HttpFields fields = response.getHeaders();
            Map<String, List<String>> ret = new HashMap<>();
            fields.forEach((field) ->
            {
                List<String> values = new ArrayList<>();
                Stream.of(field.getValues()).forEach((val) -> values.add(val));
                ret.put(field.getName(), values);
            });
            return ret;
        };

        configurator.afterResponse(handshakeResponse);
    }
}
