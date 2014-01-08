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

import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.api.UpgradeRequest;
import org.eclipse.jetty.websocket.api.UpgradeResponse;
import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.common.SessionListener;
import org.eclipse.jetty.websocket.common.WebSocketSession;
import org.eclipse.jetty.websocket.common.events.EventDriver;
import org.eclipse.jetty.websocket.common.events.EventDriverFactory;
import org.eclipse.jetty.websocket.mux.MuxChannel;
import org.eclipse.jetty.websocket.mux.MuxException;
import org.eclipse.jetty.websocket.mux.Muxer;
import org.eclipse.jetty.websocket.mux.op.MuxAddChannelResponse;

import examples.echo.AdapterEchoSocket;

/**
 * Dummy impl of MuxAddServer
 */
public class DummyMuxAddServer implements MuxAddServer
{
    @SuppressWarnings("unused")
    private static final Logger LOG = Log.getLogger(DummyMuxAddServer.class);
    private AdapterEchoSocket echo;
    private WebSocketPolicy policy;
    private EventDriverFactory eventDriverFactory;

    public DummyMuxAddServer()
    {
        this.policy = WebSocketPolicy.newServerPolicy();
        this.eventDriverFactory = new EventDriverFactory(policy);
        this.echo = new AdapterEchoSocket();
    }

    @Override
    public UpgradeRequest getPhysicalHandshakeRequest()
    {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public UpgradeResponse getPhysicalHandshakeResponse()
    {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public void handshake(Muxer muxer, MuxChannel channel, UpgradeRequest request) throws MuxException, IOException
    {
        StringBuilder response = new StringBuilder();
        response.append("HTTP/1.1 101 Switching Protocols\r\n");
        response.append("Connection: upgrade\r\n");
        // not meaningful (per Draft 08) hresp.append("Upgrade: websocket\r\n");
        // not meaningful (per Draft 08) hresp.append("Sec-WebSocket-Accept: Kgo85/8KVE8YPONSeyhgL3GwqhI=\r\n");
        response.append("\r\n");

        EventDriver websocket = this.eventDriverFactory.wrap(echo);
        WebSocketSession session = new WebSocketSession(request.getRequestURI(),websocket,channel, new SessionListener[0]);
        UpgradeResponse uresponse = new UpgradeResponse();
        uresponse.setAcceptedSubProtocol("echo");
        session.setUpgradeResponse(uresponse);
        channel.setSession(session);
        channel.setSubProtocol("echo");
        channel.onOpen();
        session.open();

        MuxAddChannelResponse addChannelResponse = new MuxAddChannelResponse();
        addChannelResponse.setChannelId(channel.getChannelId());
        addChannelResponse.setEncoding(MuxAddChannelResponse.IDENTITY_ENCODING);
        addChannelResponse.setFailed(false);
        addChannelResponse.setHandshake(response.toString());

        muxer.output(addChannelResponse);
    }
}
