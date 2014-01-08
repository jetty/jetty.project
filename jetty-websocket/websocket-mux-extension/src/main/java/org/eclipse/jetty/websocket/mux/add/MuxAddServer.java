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

package org.eclipse.jetty.websocket.mux.add;

import java.io.IOException;

import org.eclipse.jetty.websocket.api.UpgradeRequest;
import org.eclipse.jetty.websocket.api.UpgradeResponse;
import org.eclipse.jetty.websocket.common.WebSocketSession;
import org.eclipse.jetty.websocket.mux.MuxChannel;
import org.eclipse.jetty.websocket.mux.MuxException;
import org.eclipse.jetty.websocket.mux.Muxer;

/**
 * Server interface, for dealing with incoming AddChannelRequest / AddChannelResponse flows.
 */
public interface MuxAddServer
{
    public UpgradeRequest getPhysicalHandshakeRequest();

    public UpgradeResponse getPhysicalHandshakeResponse();

    /**
     * Perform the handshake.
     * 
     * @param channel
     *            the channel to attach the {@link WebSocketSession} to.
     * @param requestHandshake
     *            the request handshake (request headers)
     * @throws MuxException
     *             if unable to handshake
     * @throws IOException
     *             if unable to parse request headers
     */
    void handshake(Muxer muxer, MuxChannel channel, UpgradeRequest request) throws MuxException, IOException;
}
