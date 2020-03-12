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

<<<<<<< HEAD:jetty-websocket/websocket-javax-client/src/main/java/org/eclipse/jetty/websocket/javax/client/internal/JsrUpgradeListener.java
package org.eclipse.jetty.websocket.javax.client.internal;
=======
package org.eclipse.jetty.websocket.jakarta.client;
>>>>>>> Issue #4568 - use jakarta.websocket for jetty-11:jetty-websocket/websocket-jakarta-client/src/main/java/org/eclipse/jetty/websocket/jakarta/client/JsrUpgradeListener.java

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import jakarta.websocket.ClientEndpointConfig.Configurator;
import jakarta.websocket.HandshakeResponse;
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

        HandshakeResponse handshakeResponse = () ->
        {
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
