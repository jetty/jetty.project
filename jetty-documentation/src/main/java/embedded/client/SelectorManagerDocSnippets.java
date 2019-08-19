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

package embedded.client;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.Map;

import org.eclipse.jetty.io.SelectorManager;

public class SelectorManagerDocSnippets
{
    // tag::connect[]
    public void connect(SelectorManager selectorManager, Map<String, Object> context) throws IOException
    {
        String host = "host";
        int port = 8080;

        // Create an unconnected SocketChannel.
        SocketChannel socketChannel = SocketChannel.open();
        socketChannel.configureBlocking(false);

        // Connect and register to Jetty.
        if (socketChannel.connect(new InetSocketAddress(host, port)))
            selectorManager.accept(socketChannel, context);
        else
            selectorManager.connect(socketChannel, context);
    }
    // end::connect[]

    // tag::accept[]
    public void accept(ServerSocketChannel acceptor, SelectorManager selectorManager) throws IOException
    {
        // Wait until a client connects.
        SocketChannel socketChannel = acceptor.accept();
        socketChannel.configureBlocking(false);

        // Accept and register to Jetty.
        Object attachment = null;
        selectorManager.accept(socketChannel, attachment);
    }
    // end::accept[]
}
