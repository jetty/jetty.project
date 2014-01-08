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

import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.mux.MuxChannel;
import org.eclipse.jetty.websocket.mux.MuxDecoder;
import org.eclipse.jetty.websocket.mux.MuxEncoder;
import org.eclipse.jetty.websocket.mux.MuxOp;
import org.eclipse.jetty.websocket.mux.Muxer;
import org.eclipse.jetty.websocket.mux.helper.LocalWebSocketConnection;
import org.eclipse.jetty.websocket.mux.op.MuxAddChannelRequest;
import org.eclipse.jetty.websocket.mux.op.MuxAddChannelResponse;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;

public class MuxerAddClientTest
{
    @Rule
    public TestName testname = new TestName();

    @Test
    @Ignore("Interrim, not functional yet")
    public void testAddChannel_Client() throws Exception
    {
        // Client side physical socket
        LocalWebSocketConnection physical = new LocalWebSocketConnection(testname);
        physical.setPolicy(WebSocketPolicy.newClientPolicy());
        physical.open();

        // Server Reader
        MuxDecoder serverRead = new MuxDecoder();

        // Client side Muxer
        Muxer muxer = new Muxer(physical);
        DummyMuxAddClient addClient = new DummyMuxAddClient();
        muxer.setAddClient(addClient);
        muxer.setOutgoingFramesHandler(serverRead);

        // Server Writer
        MuxEncoder serverWrite = MuxEncoder.toIncoming(physical);

        // Build AddChannelRequest handshake data
        StringBuilder request = new StringBuilder();
        request.append("GET /echo HTTP/1.1\r\n");
        request.append("Host: localhost\r\n");
        request.append("Upgrade: websocket\r\n");
        request.append("Connection: Upgrade\r\n");
        request.append("Sec-WebSocket-Key: ZDTIRU5vU9xOfkg8JAgN3A==\r\n");
        request.append("Sec-WebSocket-Version: 13\r\n");
        request.append("\r\n");

        // Build AddChannelRequest
        long channelId = 1L;
        MuxAddChannelRequest req = new MuxAddChannelRequest();
        req.setChannelId(channelId);
        req.setEncoding((byte)0);
        req.setHandshake(request.toString());

        // Have client sent AddChannelRequest
        MuxChannel channel = muxer.getChannel(channelId,true);
        MuxEncoder clientWrite = MuxEncoder.toOutgoing(channel);
        clientWrite.op(req);

        // Have server read request
        serverRead.assertHasOp(MuxOp.ADD_CHANNEL_REQUEST,1);

        // prepare AddChannelResponse
        StringBuilder response = new StringBuilder();
        response.append("HTTP/1.1 101 Switching Protocols\r\n");
        response.append("Upgrade: websocket\r\n");
        response.append("Connection: upgrade\r\n");
        response.append("Sec-WebSocket-Accept: Kgo85/8KVE8YPONSeyhgL3GwqhI=\r\n");
        response.append("\r\n");

        MuxAddChannelResponse resp = new MuxAddChannelResponse();
        resp.setChannelId(channelId);
        resp.setFailed(false);
        resp.setEncoding((byte)0);
        resp.setHandshake(resp.toString());

        // Server writes add channel response
        serverWrite.op(resp);

        // TODO: handle the upgrade on client side.
    }
}
