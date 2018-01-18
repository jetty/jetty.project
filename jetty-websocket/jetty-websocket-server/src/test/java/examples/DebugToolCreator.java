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

package examples;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.core.extensions.ExtensionConfig;
import org.eclipse.jetty.websocket.servlet.ServletUpgradeRequest;
import org.eclipse.jetty.websocket.servlet.ServletUpgradeResponse;
import org.eclipse.jetty.websocket.servlet.WebSocketCreator;

public class DebugToolCreator implements WebSocketCreator
{
    private static final Logger LOG = Log.getLogger(DebugToolCreator.class);

    @Override
    public Object createWebSocket(ServletUpgradeRequest req, ServletUpgradeResponse resp)
    {
        LOG.debug("Creating BrowserSocket");

        if (req.getSubProtocols() != null)
        {
            if (!req.getSubProtocols().isEmpty())
            {
                String subProtocol = req.getSubProtocols().get(0);
                resp.setAcceptedSubProtocol(subProtocol);
            }
        }

        String ua = req.getHeader("User-Agent");
        String rexts = req.getHeader("Sec-WebSocket-Extensions");

        // manually negotiate extensions
        List<ExtensionConfig> negotiated = new ArrayList<>();
        // adding frame debug
        negotiated.add(new ExtensionConfig("@frame-capture; output-dir=target"));
        for (ExtensionConfig config : req.getExtensions())
        {
            if (config.getName().equals("permessage-deflate"))
            {
                // what we are interested in here
                negotiated.add(config);
                continue;
            }
            // skip all others
        }

        resp.setExtensions(negotiated);

        LOG.debug("User-Agent: {}", ua);
        LOG.debug("Sec-WebSocket-Extensions (Request) : {}", rexts);
        LOG.debug("Sec-WebSocket-Protocol (Request): {}", req.getHeader("Sec-WebSocket-Protocol"));
        LOG.debug("Sec-WebSocket-Protocol (Response): {}", resp.getAcceptedSubProtocol());

        req.getExtensions();
        return new BrowserSocket(ua, rexts);

    }
}
