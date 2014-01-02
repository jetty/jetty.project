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

import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.common.OpCode;
import org.eclipse.jetty.websocket.common.frames.TextFrame;
import org.eclipse.jetty.websocket.mux.MuxDecoder;
import org.eclipse.jetty.websocket.mux.MuxEncoder;
import org.eclipse.jetty.websocket.mux.MuxOp;
import org.eclipse.jetty.websocket.mux.Muxer;
import org.eclipse.jetty.websocket.mux.helper.LocalWebSocketConnection;
import org.eclipse.jetty.websocket.mux.op.MuxAddChannelRequest;
import org.eclipse.jetty.websocket.mux.op.MuxAddChannelResponse;
import org.junit.Assert;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;

public class MuxerAddServerTest
{
    @Rule
    public TestName testname = new TestName();

    @Test
    @Ignore("Interrim, not functional yet")
    public void testAddChannel_Server() throws Exception
    {
        // Server side physical connection
        LocalWebSocketConnection physical = new LocalWebSocketConnection(testname);
        physical.setPolicy(WebSocketPolicy.newServerPolicy());
        physical.open();

        // Client reader
        MuxDecoder clientRead = new MuxDecoder();

        // Build up server side muxer.
        Muxer muxer = new Muxer(physical);
        DummyMuxAddServer addServer = new DummyMuxAddServer();
        muxer.setAddServer(addServer);
        muxer.setOutgoingFramesHandler(clientRead);

        // Wire up physical connection to forward incoming frames to muxer
        physical.setNextIncomingFrames(muxer);

        // Client simulator
        // Can inject mux encapsulated frames into physical connection as if from
        // physical connection.
        MuxEncoder clientWrite = MuxEncoder.toIncoming(physical);

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
        MuxAddChannelRequest req = new MuxAddChannelRequest();
        req.setChannelId(1);
        req.setEncoding((byte)0);
        req.setHandshake(request.toString());

        // Have client sent AddChannelRequest
        clientWrite.op(req);

        // Make sure client got AddChannelResponse
        clientRead.assertHasOp(MuxOp.ADD_CHANNEL_RESPONSE,1);
        MuxAddChannelResponse response = (MuxAddChannelResponse)clientRead.getOps().pop();
        Assert.assertThat("AddChannelResponse.channelId",response.getChannelId(),is(1L));
        Assert.assertThat("AddChannelResponse.failed",response.isFailed(),is(false));
        Assert.assertThat("AddChannelResponse.handshake",response.getHandshake(),notNullValue());
        Assert.assertThat("AddChannelResponse.handshakeSize",response.getHandshakeSize(),is(57L));

        clientRead.reset();

        // Send simple echo request
        clientWrite.frame(1,new TextFrame().setPayload("Hello World"));

        // Test for echo response (is there a user echo websocket connected to the sub-channel?)
        clientRead.assertHasFrame(OpCode.TEXT,1L,1);
    }
}
