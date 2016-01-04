//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.server.helper;

import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.TypeUtil;
import org.junit.Assert;

public class SafariD00
{
    private URI uri;
    private SocketAddress endpoint;
    private Socket socket;
    private OutputStream out;
    private InputStream in;

    public SafariD00(URI uri)
    {
        this.uri = uri;
        this.endpoint = new InetSocketAddress(uri.getHost(),uri.getPort());
    }

    /**
     * Open the Socket to the destination endpoint and
     *
     * @return the open java Socket.
     * @throws IOException on test failure
     */
    public Socket connect() throws IOException
    {
        socket = new Socket();
        socket.connect(endpoint,1000);

        out = socket.getOutputStream();
        in = socket.getInputStream();

        return socket;
    }

    public void disconnect() throws IOException
    {
        socket.close();
    }

    /**
     * Issue an Http websocket (Draft-0) upgrade request using the Safari particulars.
     *
     * @throws IOException on test failure
     */
    public void issueHandshake() throws IOException
    {
        StringBuilder req = new StringBuilder();
        req.append("GET ").append(uri.getPath()).append(" HTTP/1.1\r\n");
        req.append("Upgrade: WebSocket\r\n");
        req.append("Connection: Upgrade\r\n");
        req.append("Host: ").append(uri.getHost()).append(":").append(uri.getPort()).append("\r\n");
        req.append("Origin: http://www.google.com/\r\n");
        req.append("Sec-WebSocket-Key1: 15{ft  :6@87  0 M 5 c901\r\n");
        req.append("Sec-WebSocket-Key2: 3? C;7~0 8   \" 3 2105 6  `_ {\r\n");
        req.append("\r\n");

        // System.out.printf("--- Request ---%n%s",req);

        byte reqBytes[] = req.toString().getBytes(StandardCharsets.UTF_8);
        byte hixieBytes[] = TypeUtil.fromHexString("e739617916c9daf3");
        byte buf[] = new byte[reqBytes.length + hixieBytes.length];
        System.arraycopy(reqBytes,0,buf,0,reqBytes.length);
        System.arraycopy(hixieBytes,0,buf,reqBytes.length,hixieBytes.length);

        // Send HTTP GET Request (with hixie bytes)
        out.write(buf,0,buf.length);
        out.flush();

        // Read HTTP 101 Upgrade / Handshake Response
        InputStreamReader reader = new InputStreamReader(in);
        BufferedReader br = new BufferedReader(reader);

        socket.setSoTimeout(5000);

        boolean foundEnd = false;
        String line;
        while (!foundEnd)
        {
            line = br.readLine();
            // System.out.printf("RESP: %s%n",line);
            Assert.assertThat(line, notNullValue());
            if (line.length() == 0)
            {
                foundEnd = true;
            }
        }

        // Read expected handshake hixie bytes
        byte hixieHandshakeExpected[] = TypeUtil.fromHexString("c7438d956cf611a6af70603e6fa54809");
        byte hixieHandshake[] = new byte[hixieHandshakeExpected.length];

        int readLen = in.read(hixieHandshake,0,hixieHandshake.length);
        Assert.assertThat("Read hixie handshake bytes",readLen,is(hixieHandshake.length));
    }

    public void sendMessage(String... msgs) throws IOException
    {
        int len = 0;
        for (String msg : msgs)
        {
            len += (msg.length() + 2);
        }

        ByteBuffer buf = ByteBuffer.allocate(len);

        for (String msg : msgs)
        {
            buf.put((byte)0x00);
            buf.put(msg.getBytes(StandardCharsets.UTF_8));
            buf.put((byte)0xFF);
        }

        BufferUtil.writeTo(buf,out);
        out.flush();
    }
}
