//
//  ========================================================================
//  Copyright (c) 1995-2012 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.server.mux;

import java.io.IOException;

import org.eclipse.jetty.websocket.core.extensions.mux.MuxChannel;
import org.eclipse.jetty.websocket.core.extensions.mux.MuxException;
import org.eclipse.jetty.websocket.core.extensions.mux.add.MuxAddServer;

/**
 * Handler for incoming MuxAddChannel requests.
 */
public class MuxAddHandler implements MuxAddServer
{
    /**
     * An incoming MuxAddChannel request.
     * 
     * @param the
     *            channel this request should be bound to
     * @param requestHandshake
     *            the incoming request headers
     * @return the outgoing response headers
     */
    @Override
    public String handshake(MuxChannel channel, String requestHandshake) throws MuxException, IOException
    {
        // Need to call into HttpChannel to get the websocket properly setup.

        return null;
    }
}
