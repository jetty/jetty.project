//
//  ========================================================================
//  Copyright (c) 1995-2019 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.javax.tests.server;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import javax.websocket.OnMessage;
import javax.websocket.Session;
import javax.websocket.server.ServerContainer;
import javax.websocket.server.ServerEndpoint;

import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.core.CloseStatus;
import org.eclipse.jetty.websocket.core.Frame;
import org.eclipse.jetty.websocket.core.OpCode;
import org.eclipse.jetty.websocket.javax.tests.DataUtils;
import org.eclipse.jetty.websocket.javax.tests.Fuzzer;
import org.eclipse.jetty.websocket.javax.tests.LocalServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

public class TextStreamTest
{
    private static final Logger LOG = Log.getLogger(TextStreamTest.class);

    private static LocalServer server;
    private static ServerContainer container;

    @BeforeAll
    public static void startServer() throws Exception
    {
        server = new LocalServer();
        server.start();
        container = server.getServerContainer();
        container.addEndpoint(ServerTextStreamer.class);
    }

    @AfterAll
    public static void stopServer() throws Exception
    {
        server.stop();
    }

    @Test
    public void testWith1kMessage() throws Exception
    {
        testEcho(1024);
    }

    private byte[] newData(int size)
    {
        @SuppressWarnings("SpellCheckingInspection")
        byte[] pattern = "01234567890abcdefghijlklmopqrstuvwxyz".getBytes(StandardCharsets.UTF_8);
        byte[] data = new byte[size];
        for (int i = 0; i < size; i++)
        {
            data[i] = pattern[i % pattern.length];
        }
        return data;
    }

    private void testEcho(int size) throws Exception
    {
        byte[] data = newData(size);

        List<Frame> send = new ArrayList<>();
        send.add(new Frame(OpCode.TEXT).setPayload(ByteBuffer.wrap(data)));
        send.add(CloseStatus.toFrame(CloseStatus.NORMAL));

        ByteBuffer expectedMessage = DataUtils.copyOf(data);
        List<Frame> expect = new ArrayList<>();
        expect.add(new Frame(OpCode.TEXT).setPayload(expectedMessage));
        expect.add(CloseStatus.toFrame(CloseStatus.NORMAL));

        try (Fuzzer fuzzer = server.newNetworkFuzzer("/echo"))
        {
            fuzzer.sendBulk(send);
            fuzzer.expect(expect);
        }
    }

    // TODO These tests incorrectly assumes no frame fragmentation.
    // When message fragmentation is implemented in PartialStringMessageSink then update
    // this test to check on the server side for no buffers larger than the maxTextMessageBufferSize.

    @Disabled
    @Test
    public void testAtMaxDefaultMessageBufferSize() throws Exception
    {
        testEcho(container.getDefaultMaxTextMessageBufferSize());
    }

    @Disabled
    @Test
    public void testLargerThenMaxDefaultMessageBufferSize() throws Exception
    {
        int size = container.getDefaultMaxTextMessageBufferSize() + 16;
        byte[] data = newData(size);

        List<Frame> send = new ArrayList<>();
        send.add(new Frame(OpCode.TEXT).setPayload(ByteBuffer.wrap(data)));
        send.add(CloseStatus.toFrame(CloseStatus.NORMAL));

        // make copy of raw data (to avoid client masking during send)
        byte[] expectedData = new byte[data.length];
        System.arraycopy(data, 0, expectedData, 0, data.length);

        // Frames expected are influenced by container.getDefaultMaxTextMessageBufferSize setting
        ByteBuffer frame1 = ByteBuffer.wrap(expectedData, 0, container.getDefaultMaxTextMessageBufferSize());
        ByteBuffer frame2 = ByteBuffer
            .wrap(expectedData, container.getDefaultMaxTextMessageBufferSize(), size - container.getDefaultMaxTextMessageBufferSize());
        List<Frame> expect = new ArrayList<>();
        expect.add(new Frame(OpCode.TEXT).setPayload(frame1).setFin(false));
        expect.add(new Frame(OpCode.CONTINUATION).setPayload(frame2).setFin(true));
        expect.add(CloseStatus.toFrame(CloseStatus.NORMAL));

        try (Fuzzer fuzzer = server.newNetworkFuzzer("/echo"))
        {
            fuzzer.sendBulk(send);
            fuzzer.expect(expect);
        }
    }

    @ServerEndpoint("/echo")
    public static class ServerTextStreamer
    {
        @OnMessage
        public void echo(Session session, Reader input) throws IOException
        {
            char[] buffer = new char[128];
            try (Writer output = session.getBasicRemote().getSendWriter())
            {
                long totalRead = 0;
                int read;
                while ((read = input.read(buffer)) >= 0)
                {
                    totalRead += read;
                    output.write(buffer, 0, read);
                }

                LOG.debug("{} total bytes read/write", totalRead);
            }
        }
    }
}
