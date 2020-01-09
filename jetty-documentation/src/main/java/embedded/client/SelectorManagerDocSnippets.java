//
// ========================================================================
// Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under
// the terms of the Eclipse Public License 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0
//
// This Source Code may also be made available under the following
// Secondary Licenses when the conditions for such availability set
// forth in the Eclipse Public License, v. 2.0 are satisfied:
// the Apache License v2.0 which is available at
// https://www.apache.org/licenses/LICENSE-2.0
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
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
