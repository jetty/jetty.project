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

package org.eclipse.jetty.unixsocket;

import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.CharBuffer;
import java.nio.channels.Channels;
import java.util.Date;

import jnr.unixsocket.UnixSocketAddress;
import jnr.unixsocket.UnixSocketChannel;

public class UnixSocketClient
{
    public static void main(String[] args) throws Exception
    {
        java.io.File path = new java.io.File("/tmp/jetty.sock");
        String data = "GET / HTTP/1.1\r\nHost: unixsock\r\n\r\n";
        UnixSocketAddress address = new UnixSocketAddress(path);
        UnixSocketChannel channel = UnixSocketChannel.open(address);
        System.out.println("connected to " + channel.getRemoteSocketAddress());
        
        PrintWriter w = new PrintWriter(Channels.newOutputStream(channel));
        InputStreamReader r = new InputStreamReader(Channels.newInputStream(channel));
        
        while (true)
        {
            w.print(data);
            w.flush();

            CharBuffer result = CharBuffer.allocate(4096);
            r.read(result);
            result.flip();
            System.out.println("read from server: " + result.toString());
            
            Thread.sleep(1000);
        }
    }
}

