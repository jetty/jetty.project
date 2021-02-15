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

import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import jnr.enxio.channels.NativeSelectorProvider;
import jnr.unixsocket.UnixServerSocketChannel;
import jnr.unixsocket.UnixSocketAddress;
import jnr.unixsocket.UnixSocketChannel;

public class JnrTest
{
    public static void main(String... args) throws Exception
    {
        java.io.File path = new java.io.File("/tmp/fubar.sock");
        path.deleteOnExit();
        UnixSocketAddress address = new UnixSocketAddress(path);

        UnixServerSocketChannel serverChannel = UnixServerSocketChannel.open();
        Selector serverSelector = NativeSelectorProvider.getInstance().openSelector();
        serverChannel.configureBlocking(false);
        serverChannel.socket().bind(address);
        serverChannel.register(serverSelector, SelectionKey.OP_ACCEPT, "SERVER");
        System.err.printf("serverChannel=%s,%n", serverChannel);

        UnixSocketChannel client = UnixSocketChannel.open(address);
        Selector clientSelector = NativeSelectorProvider.getInstance().openSelector();
        client.configureBlocking(false);
        SelectionKey clientKey = client.register(clientSelector, 0, "client");

        System.err.printf("client=%s connected=%b pending=%b%n", client, client.isConnected(), client.isConnectionPending());

        int selected = serverSelector.select();
        System.err.printf("serverSelected=%d %s%n", selected, serverSelector.selectedKeys());

        SelectionKey key = serverSelector.selectedKeys().iterator().next();
        serverSelector.selectedKeys().clear();
        System.err.printf("key=%s/%s c=%b a=%b r=%b w=%b%n", key, key.attachment(), key.isConnectable(), key.isAcceptable(), key.isReadable(), key.isWritable());

        UnixSocketChannel server = serverChannel.accept();
        server.configureBlocking(false);
        SelectionKey serverKey = server.register(serverSelector, SelectionKey.OP_READ, "server");
        System.err.printf("server=%s key=%s connected=%b pending=%b%n", server, serverKey, server.isConnected(), server.isConnectionPending());

        selected = serverSelector.selectNow();
        System.err.printf("serverSelected=%d %s%n", selected, serverSelector.selectedKeys());

        ByteBuffer buffer = ByteBuffer.allocate(32768);

        buffer.clear();
        int read = server.read(buffer);
        buffer.flip();
        System.err.printf("server read=%d%n", read);

        selected = clientSelector.selectNow();
        System.err.printf("clientSelected=%d %s%n", selected, clientSelector.selectedKeys());

        int wrote = client.write(ByteBuffer.wrap("Hello".getBytes(StandardCharsets.ISO_8859_1)));
        System.err.printf("client wrote=%d%n", wrote);

        selected = serverSelector.selectNow();
        System.err.printf("serverSelected=%d %s%n", selected, serverSelector.selectedKeys());
        key = serverSelector.selectedKeys().iterator().next();
        serverSelector.selectedKeys().clear();
        System.err.printf("key=%s/%s c=%b a=%b r=%b w=%b ch=%s%n", key, key.attachment(), key.isConnectable(), key.isAcceptable(), key.isReadable(), key.isWritable(), key.channel());

        buffer.clear();
        read = server.read(buffer);
        buffer.flip();
        System.err.printf("server read=%d '%s'%n", read, new String(buffer.array(), 0, buffer.limit(), StandardCharsets.ISO_8859_1));

        selected = clientSelector.selectNow();
        System.err.printf("clientSelected=%d %s%n", selected, clientSelector.selectedKeys());

        wrote = server.write(ByteBuffer.wrap("Ciao!".getBytes(StandardCharsets.ISO_8859_1)));
        System.err.printf("server wrote=%d%n", wrote);

        selected = clientSelector.selectNow();
        System.err.printf("clientSelected=%d %s%n", selected, clientSelector.selectedKeys());

        clientKey.interestOps(SelectionKey.OP_READ);

        selected = clientSelector.selectNow();
        System.err.printf("clientSelected=%d %s%n", selected, clientSelector.selectedKeys());
        key = clientSelector.selectedKeys().iterator().next();
        clientSelector.selectedKeys().clear();
        System.err.printf("key=%s/%s c=%b a=%b r=%b w=%b ch=%s%n", key, key.attachment(), key.isConnectable(), key.isAcceptable(), key.isReadable(), key.isWritable(), key.channel());

        buffer.clear();
        read = client.read(buffer);
        buffer.flip();
        System.err.printf("client read=%d '%s'%n", read, new String(buffer.array(), 0, buffer.limit(), StandardCharsets.ISO_8859_1));

        System.err.println("So far so good.... now it gets strange...");

        // Let's write until flow control hit

        int size = buffer.capacity();
        Arrays.fill(buffer.array(), 0, size, (byte)'X');
        long written = 0;
        while (true)
        {
            buffer.position(0).limit(size);
            wrote = server.write(buffer);

            System.err.printf("server wrote %d/%d remaining=%d%n", wrote, size, buffer.remaining());

            if (buffer.remaining() != (size - wrote))
                System.err.printf("BUG!!!!!!!!!!!!!!!!%n");

            if (wrote == 0)
                break;
            written += wrote;
        }

        System.err.printf("server wrote %d before flow control%n", written);

        selected = clientSelector.selectNow();
        System.err.printf("clientSelected=%d %s%n", selected, clientSelector.selectedKeys());
        key = clientSelector.selectedKeys().iterator().next();
        clientSelector.selectedKeys().clear();
        System.err.printf("key=%s/%s c=%b a=%b r=%b w=%b ch=%s%n", key, key.attachment(), key.isConnectable(), key.isAcceptable(), key.isReadable(), key.isWritable(), key.channel());

        buffer.clear();
        buffer.limit(32);
        read = client.read(buffer);
        buffer.flip();
        System.err.printf("client read=%d '%s'%n", read, new String(buffer.array(), 0, buffer.limit(), StandardCharsets.ISO_8859_1));

        server.close();
        client.close();
    }
}
