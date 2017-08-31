//
//  ========================================================================
//  Copyright (c) 1995-2017 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.client.impl;

import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.websocket.client.ClientUpgradeRequest;
import org.eclipse.jetty.websocket.core.extensions.ExtensionConfig;

public class ClientUpgradeRequestInternal extends ClientUpgradeRequest
{
    public ClientUpgradeRequestInternal(URI requestURI)
    {
        super();
        setRequestURI(requestURI);
    }

    public ClientUpgradeRequestInternal(WebSocketUpgradeRequest wsRequest)
    {
        this(wsRequest.getURI());
        // cookies
        this.setCookies(wsRequest.getCookies());
        // headers
        Map<String, List<String>> headers = new HashMap<>();
        HttpFields fields = wsRequest.getHeaders();
        for (HttpField field : fields)
        {
            String key = field.getName();
            List<String> values = headers.get(key);
            if (values == null)
            {
                values = new ArrayList<>();
            }
            values.addAll(Arrays.asList(field.getValues()));
            headers.put(key,values);
            // sub protocols
            if(key.equalsIgnoreCase("Sec-WebSocket-Protocol"))
            {
                for(String subProtocol: field.getValue().split(","))
                {
                    setSubProtocols(subProtocol);
                }
            }
            // extensions
            if(key.equalsIgnoreCase("Sec-WebSocket-Extensions"))
            {
                for(ExtensionConfig ext: ExtensionConfig.parseList(field.getValues()))
                {
                    addExtensions(ext);
                }
            }
        }
        super.setHeaders(headers);
        // sessions
        setHttpVersion(wsRequest.getVersion().toString());
        setMethod(wsRequest.getMethod());
    }
}
