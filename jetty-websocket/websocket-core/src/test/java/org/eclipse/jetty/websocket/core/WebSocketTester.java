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

package org.eclipse.jetty.websocket.core;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.io.ArrayByteBufferPool;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.websocket.core.internal.Parser;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;

public class WebSocketTester
{
    private static String NON_RANDOM_KEY = Base64.getEncoder().encodeToString("0123456701234567".getBytes());
    private static SslContextFactory.Client sslContextFactory;
    protected ByteBufferPool bufferPool;
    protected Parser parser;

    @BeforeAll
    public static void startSslContextFactory() throws Exception
    {
        sslContextFactory = new SslContextFactory.Client(true);
        sslContextFactory.setEndpointIdentificationAlgorithm("");
        sslContextFactory.start();
    }

    @AfterAll
    public static void stopSslContextFactory() throws Exception
    {
        sslContextFactory.stop();
    }

    @BeforeEach
    public void before()
    {
        bufferPool = new ArrayByteBufferPool();
        parser = new Parser(bufferPool);
    }

    protected Socket newClient(int port) throws Exception
    {
        return newClient(port, false, null);
    }

    protected Socket newClient(int port, boolean tls) throws Exception
    {
        return newClient(port, tls, null);
    }

    protected Socket newClient(int port, String extensions) throws Exception
    {
        return newClient(port, false, extensions);
    }

    protected static HttpFields newUpgradeRequest(String extensions)
    {
        HttpFields fields = new HttpFields();
        fields.add(HttpHeader.HOST, "127.0.0.1");
        fields.add(HttpHeader.UPGRADE, "websocket");
        fields.add(HttpHeader.CONNECTION, "Upgrade");
        fields.add(HttpHeader.SEC_WEBSOCKET_KEY, NON_RANDOM_KEY);
        fields.add(HttpHeader.SEC_WEBSOCKET_VERSION, "13");
        fields.add(HttpHeader.PRAGMA, "no-cache");
        fields.add(HttpHeader.CACHE_CONTROL, "no-cache");
        fields.add(HttpHeader.SEC_WEBSOCKET_SUBPROTOCOL, "test");
        if (extensions != null)
            fields.add(HttpHeader.SEC_WEBSOCKET_EXTENSIONS, extensions);

        return fields;
    }

    protected static String getUpgradeResponse(InputStream in) throws IOException
    {
        int state = 0;
        StringBuilder buffer = new StringBuilder();
        while (state < 4)
        {
            int i = in.read();
            if (i < 0)
                throw new EOFException();
            int b = (byte)(i & 0xff);
            buffer.append((char)b);
            switch (state)
            {
                case 0:
                    state = (b == '\r') ? 1 : 0;
                    break;
                case 1:
                    state = (b == '\n') ? 2 : 0;
                    break;
                case 2:
                    state = (b == '\r') ? 3 : 0;
                    break;
                case 3:
                    state = (b == '\n') ? 4 : 0;
                    break;
                default:
                    state = 0;
            }
        }

        return buffer.toString();
    }

    protected Socket newClient(int port, boolean tls, String extensions) throws Exception
    {
        Socket client;
        if (!tls)
            client = new Socket();
        else
            client = sslContextFactory.newSslSocket();

        client.connect(new InetSocketAddress("127.0.0.1", port));

        String upgradeRequest = "GET / HTTP/1.1\r\n" + newUpgradeRequest(extensions);
        client.getOutputStream().write(upgradeRequest.getBytes(StandardCharsets.ISO_8859_1));
        String response = getUpgradeResponse(client.getInputStream());

        assertThat(response, startsWith("HTTP/1.1 101 Switching Protocols"));
        assertThat(response, containsString("Sec-WebSocket-Protocol: test"));
        assertThat(response, containsString("Sec-WebSocket-Accept: +WahVcVmeMLKQUMm0fvPrjSjwzI="));

        client.setSoTimeout(10000);
        return client;
    }

    protected Parser.ParsedFrame receiveFrame(InputStream in) throws IOException
    {
        ByteBuffer buffer = bufferPool.acquire(4096, false);
        while (true)
        {
            int p = BufferUtil.flipToFill(buffer);
            int len = in.read(buffer.array(), buffer.arrayOffset() + buffer.position(), buffer.remaining());
            if (len < 0)
                return null;
            buffer.position(buffer.position() + len);
            BufferUtil.flipToFlush(buffer, p);

            Parser.ParsedFrame frame = parser.parse(buffer);
            if (frame != null)
                return frame;
        }
    }

    protected void receiveEof(InputStream in) throws IOException
    {
        ByteBuffer buffer = bufferPool.acquire(4096, false);
        while (true)
        {
            BufferUtil.flipToFill(buffer);
            int len = in.read(buffer.array(), buffer.arrayOffset() + buffer.position(), buffer.remaining());
            if (len < 0)
                return;

            throw new IllegalStateException("unexpected content");
        }
    }
}
