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

package org.eclipse.jetty.websocket.core.extensions.mux.add;

import java.io.IOException;
import java.nio.ByteBuffer;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.core.api.WebSocketConnection;
import org.eclipse.jetty.websocket.core.extensions.mux.op.MuxAddChannelRequest;
import org.eclipse.jetty.websocket.core.extensions.mux.op.MuxAddChannelResponse;

/**
 * Dummy impl of MuxAddServer
 */
public class DummyMuxAddServer implements MuxAddServer
{
    private static final Logger LOG = Log.getLogger(DummyMuxAddServer.class);

    @Override
    public MuxAddChannelResponse handshake(WebSocketConnection physicalConnection, MuxAddChannelRequest request) throws IOException
    {

        MuxAddChannelResponse response = new MuxAddChannelResponse();
        response.setChannelId(request.getChannelId());
        response.setEnc((byte)0);

        StringBuilder hresp = new StringBuilder();
        hresp.append("HTTP/1.1 101 Switching Protocols\r\n");
        hresp.append("Connection: upgrade\r\n");
        // not meaningful (per Draft 08) hresp.append("Upgrade: websocket\r\n");
        // not meaningful (per Draft 08) hresp.append("Sec-WebSocket-Accept: Kgo85/8KVE8YPONSeyhgL3GwqhI=\r\n");
        hresp.append("\r\n");

        ByteBuffer handshake = BufferUtil.toBuffer(hresp.toString());
        LOG.debug("Handshake: {}",BufferUtil.toDetailString(handshake));

        response.setHandshake(handshake);

        return response;
    }
}
