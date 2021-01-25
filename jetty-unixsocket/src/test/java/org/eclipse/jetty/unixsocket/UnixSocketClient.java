//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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

package org.eclipse.jetty.unixsocket;

import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.CharBuffer;
import java.nio.channels.Channels;
import java.nio.charset.StandardCharsets;

import jnr.unixsocket.UnixSocketAddress;
import jnr.unixsocket.UnixSocketChannel;
import org.eclipse.jetty.toolchain.test.IO;

public class UnixSocketClient
{
    public static void main(String[] args) throws Exception
    {
        java.io.File path = new java.io.File("/tmp/jetty.sock");
        java.io.File content = new java.io.File("/tmp/data.txt");

        String method = "GET";
        int contentLength = 0;
        String body = null;
        if (content.exists())
        {
            method = "POST";
            body = IO.readToString(content);
            contentLength = body.length();
        }
        String data = method + " / HTTP/1.1\r\n" +
            "Host: unixsock\r\n" +
            "Content-Length: " + contentLength + "\r\n" +
            "Connection: close\r\n" +
            "\r\n";
        if (body != null)
            data += body;

        while (true)
        {
            UnixSocketAddress address = new UnixSocketAddress(path);
            UnixSocketChannel channel = UnixSocketChannel.open(address);
            System.out.println("connected to " + channel.getRemoteSocketAddress());

            PrintWriter w = new PrintWriter(new OutputStreamWriter(Channels.newOutputStream(channel), StandardCharsets.ISO_8859_1));
            InputStreamReader r = new InputStreamReader(Channels.newInputStream(channel));

            w.print(data);
            w.flush();

            CharBuffer result = CharBuffer.allocate(4096);
            String total = "";
            int l = 0;
            while (l >= 0)
            {
                if (l > 0)
                {
                    result.flip();
                    total += result.toString();
                }
                result.clear();
                l = r.read(result);
            }
            System.out.println("read from server: " + total);
        }
    }
}

