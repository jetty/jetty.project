//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.websocket.core.extensions;

import java.net.URI;
import java.nio.ByteBuffer;
import java.util.Random;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.io.ByteBufferCallbackAccumulator;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.BlockingArrayQueue;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.websocket.core.CloseStatus;
import org.eclipse.jetty.websocket.core.CoreSession;
import org.eclipse.jetty.websocket.core.Frame;
import org.eclipse.jetty.websocket.core.FrameHandler;
import org.eclipse.jetty.websocket.core.OpCode;
import org.eclipse.jetty.websocket.core.TestFrameHandler;
import org.eclipse.jetty.websocket.core.client.CoreClientUpgradeRequest;
import org.eclipse.jetty.websocket.core.client.WebSocketCoreClient;
import org.eclipse.jetty.websocket.core.server.WebSocketNegotiator;
import org.eclipse.jetty.websocket.core.server.WebSocketUpgradeHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class PermessageDeflateDemandTest
{
    private Server _server;
    private WebSocketCoreClient _client;
    private ServerConnector _connector;
    private WebSocketUpgradeHandler _upgradeHandler;

    @BeforeEach
    public void before() throws Exception
    {
        _server = new Server();
        _connector = new ServerConnector(_server);
        _server.addConnector(_connector);

        _upgradeHandler = new WebSocketUpgradeHandler();
        _server.setHandler(_upgradeHandler);
        _server.start();

        _client = new WebSocketCoreClient();
        _client.start();
    }

    @AfterEach
    public void after() throws Exception
    {
        _client.stop();
        _server.stop();
    }

    @Test
    public void test() throws Exception
    {
        ServerHandler serverHandler = new ServerHandler();
        _upgradeHandler.addMapping("/", WebSocketNegotiator.from(n -> serverHandler));

        TestFrameHandler clientHandler = new TestFrameHandler();
        URI uri = URI.create("ws://localhost:" + _connector.getLocalPort());
        CoreClientUpgradeRequest upgradeRequest = CoreClientUpgradeRequest.from(_client, uri, clientHandler);
        upgradeRequest.addExtensions("permessage-deflate");

        CoreSession coreSession = _client.connect(upgradeRequest).get(5, TimeUnit.SECONDS);
        assertNotNull(coreSession);
        // Set max frame size to autoFragment the message into multiple frames.
        ByteBuffer message = randomBytes(1024);
        coreSession.setMaxFrameSize(64);
        coreSession.sendFrame(new Frame(OpCode.BINARY, message).setFin(true), Callback.NOOP, false);

        coreSession.close(CloseStatus.NORMAL, null, Callback.NOOP);
        assertTrue(clientHandler.closed.await(5, TimeUnit.SECONDS));
        assertThat(clientHandler.closeStatus.getCode(), equalTo(CloseStatus.NORMAL));

        assertThat(serverHandler.binaryMessages.size(), equalTo(1));
        ByteBuffer recvMessage = serverHandler.binaryMessages.poll();
        assertThat(recvMessage, equalTo(message));
    }

    private static ByteBuffer randomBytes(int size)
    {
        var bytes = new byte[size];
        new Random(42).nextBytes(bytes);
        return BufferUtil.toBuffer(bytes);
    }

    public static class ServerHandler implements FrameHandler
    {
        private CoreSession _coreSession;
        private byte _messageType;
        public BlockingQueue<String> textMessages = new BlockingArrayQueue<>();
        public BlockingQueue<ByteBuffer> binaryMessages = new BlockingArrayQueue<>();
        private StringBuilder _stringBuilder = new StringBuilder();
        private ByteBufferCallbackAccumulator _byteBuilder = new ByteBufferCallbackAccumulator();

        @Override
        public void onOpen(CoreSession coreSession, Callback callback)
        {
            _coreSession = coreSession;
            callback.succeeded();
            coreSession.demand(1);
        }

        @Override
        public void onFrame(Frame frame, Callback callback)
        {
            if (frame.isDataFrame())
            {
                switch (frame.getOpCode())
                {
                    case OpCode.TEXT:
                        _messageType = OpCode.TEXT;
                        break;
                    case OpCode.BINARY:
                        _messageType = OpCode.BINARY;
                        break;
                    case OpCode.CONTINUATION:
                        break;
                    default:
                        throw new IllegalStateException(OpCode.name(frame.getOpCode()));
                }

                switch (_messageType)
                {
                    case OpCode.TEXT:
                        _stringBuilder.append(frame.getPayloadAsUTF8());
                        callback.succeeded();
                        if (frame.isFin())
                        {
                            textMessages.add(_stringBuilder.toString());
                            _stringBuilder = new StringBuilder();
                        }
                        break;
                    case OpCode.BINARY:
                        _byteBuilder.addEntry(frame.getPayload(), callback);
                        if (frame.isFin())
                        {
                            binaryMessages.add(BufferUtil.toBuffer(_byteBuilder.takeByteArray()));
                            _byteBuilder = new ByteBufferCallbackAccumulator();
                        }
                        break;
                    default:
                        throw new IllegalStateException(OpCode.name(_messageType));
                }
            }
            else
            {
                callback.succeeded();
            }

            _coreSession.demand(1);
        }

        @Override
        public void onError(Throwable cause, Callback callback)
        {
            cause.printStackTrace();
            callback.succeeded();
        }

        @Override
        public void onClosed(CloseStatus closeStatus, Callback callback)
        {
            callback.succeeded();
        }

        @Override
        public boolean isDemanding()
        {
            return true;
        }
    }
}
