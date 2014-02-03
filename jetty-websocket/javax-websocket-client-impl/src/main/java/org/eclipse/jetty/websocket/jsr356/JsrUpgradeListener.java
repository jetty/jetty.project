//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.jsr356;

import java.net.HttpCookie;
import java.util.List;
import java.util.Map;
import javax.websocket.ClientEndpointConfig.Configurator;

import org.eclipse.jetty.websocket.api.UpgradeRequest;
import org.eclipse.jetty.websocket.api.UpgradeResponse;
import org.eclipse.jetty.websocket.client.io.UpgradeListener;

public class JsrUpgradeListener implements UpgradeListener
{
    private Configurator configurator;

    public JsrUpgradeListener(Configurator configurator)
    {
        this.configurator = configurator;
    }

    @Override
    public void onHandshakeRequest(UpgradeRequest request)
    {
        if (configurator == null)
        {
            return;
        }

        Map<String, List<String>> headers = request.getHeaders();
        configurator.beforeRequest(headers);

        // Handle cookies
        for (String name : headers.keySet())
        {
            if ("cookie".equalsIgnoreCase(name))
            {
                List<String> values = headers.get(name);
                if (values != null)
                {
                    for (String cookie : values)
                    {
                        List<HttpCookie> cookies = HttpCookie.parse(cookie);
                        request.getCookies().addAll(cookies);
                    }
                }
            }
        }
    }

    @Override
    public void onHandshakeResponse(UpgradeResponse response)
    {
        if (configurator == null)
        {
            return;
        }

        JsrHandshakeResponse hr = new JsrHandshakeResponse(response);
        configurator.afterResponse(hr);
    }
}
