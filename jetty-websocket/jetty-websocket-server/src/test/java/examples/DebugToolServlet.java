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

import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.servlet.WebSocketServlet;
import org.eclipse.jetty.websocket.servlet.WebSocketServletFactory;

public class DebugToolServlet extends WebSocketServlet
{
    private static final Logger LOG = Log.getLogger(DebugToolServlet.class);

    @Override
    public void configure(WebSocketServletFactory factory)
    {
        LOG.debug("Configuring WebSocketServletFactory ...");

        // Registering Frame Debug
        // factory.getExtensionRegistry().register("@frame-capture",FrameCaptureExtension.class);

        // Disable permessage-deflate
        factory.getExtensionRegistry().unregister("permessage-deflate");

        // Setup the desired Socket to use for all incoming upgrade requests
        factory.setCreator(new DebugToolCreator());

        // Set the timeout
        factory.getPolicy().setIdleTimeout(30000);

        // Set top end message size
        factory.getPolicy().setMaxTextMessageSize(15 * 1024 * 1024);

    }
}
