//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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

package examples;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import javax.websocket.ClientEndpointConfig;

/**
 * Provide a means to set the `Origin` header for outgoing WebSocket upgrade requests
 */
public class OriginServerConfigurator extends ClientEndpointConfig.Configurator
{
    private final String originServer;

    public OriginServerConfigurator(String originServer)
    {
        this.originServer = originServer;
    }

    @Override
    public void beforeRequest(Map<String, List<String>> headers)
    {
        headers.put("Origin", Collections.singletonList(originServer));
        super.beforeRequest(headers);
    }
}
