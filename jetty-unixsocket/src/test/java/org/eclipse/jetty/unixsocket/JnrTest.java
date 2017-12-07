//
//  ========================================================================
//  Copyright (c) 1995-2017 Mort Bay Consulting Pty. Ltd.
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

import org.eclipse.jetty.util.BufferUtil;

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
        SelectionKey acceptKey = serverChannel.register(serverSelector, SelectionKey.OP_ACCEPT, "SERVER");
        System.err.printf("serverChannel=%s,%n",serverChannel);
        
        UnixSocketChannel client = UnixSocketChannel.open( address );
        Selector clientSelector = NativeSelectorProvider.getInstance().openSelector();
        client.configureBlocking(false);
        SelectionKey clientKey = client.register(clientSelector,0,"client");
        
        System.err.printf("client=%s connected=%b pending=%b%n",client,client.isConnected(),client.isConnectionPending());

        int selected = serverSelector.select();
        System.err.printf("serverSelected=%d %s%n",selected,serverSelector.selectedKeys());
        
        SelectionKey key = serverSelector.selectedKeys().iterator().next();
        serverSelector.selectedKeys().clear();
        System.err.printf("key=%s/%s c=%b a=%b r=%b w=%b%n",key,key.attachment(),key.isConnectable(),key.isAcceptable(),key.isReadable(),key.isWritable());

        UnixSocketChannel server = serverChannel.accept();
        server.configureBlocking(false);
        SelectionKey serverKey = server.register(serverSelector, SelectionKey.OP_READ, "server");
        System.err.printf("server=%s connected=%b pending=%b%n",server,server.isConnected(),server.isConnectionPending());
        
        selected = serverSelector.selectNow();
        System.err.printf("serverSelected=%d %s%n",selected,serverSelector.selectedKeys());
        
        ByteBuffer buffer = BufferUtil.allocate(1024);

        BufferUtil.clearToFill(buffer);
        int read = server.read(buffer);
        BufferUtil.flipToFlush(buffer,0);
        System.err.printf("server read=%d%n",read);

        
        selected = clientSelector.selectNow();
        System.err.printf("clientSelected=%d %s%n",selected,clientSelector.selectedKeys());
        
        int wrote = client.write(BufferUtil.toBuffer("Hello"));
        System.err.printf("client wrote=%d%n",wrote);
        
        selected = serverSelector.selectNow();
        System.err.printf("serverSelected=%d %s%n",selected,serverSelector.selectedKeys());
        key = serverSelector.selectedKeys().iterator().next();
        serverSelector.selectedKeys().clear();
        System.err.printf("key=%s/%s c=%b a=%b r=%b w=%b ch=%s%n",key,key.attachment(),key.isConnectable(),key.isAcceptable(),key.isReadable(),key.isWritable(),key.channel());

        BufferUtil.clearToFill(buffer);
        read = server.read(buffer);
        BufferUtil.flipToFlush(buffer,0);
        System.err.printf("server read=%d '%s'%n",read,BufferUtil.toString(buffer));


        
        selected = clientSelector.selectNow();
        System.err.printf("clientSelected=%d %s%n",selected,clientSelector.selectedKeys());

        wrote = server.write(BufferUtil.toBuffer("Ciao!"));
        System.err.printf("server wrote=%d%n",wrote);
        
        selected = clientSelector.selectNow();
        System.err.printf("clientSelected=%d %s%n",selected,clientSelector.selectedKeys());
        
        clientKey.interestOps(SelectionKey.OP_READ);
        
        selected = clientSelector.selectNow();
        System.err.printf("clientSelected=%d %s%n",selected,clientSelector.selectedKeys());
        key = clientSelector.selectedKeys().iterator().next();
        clientSelector.selectedKeys().clear();
        System.err.printf("key=%s/%s c=%b a=%b r=%b w=%b ch=%s%n",key,key.attachment(),key.isConnectable(),key.isAcceptable(),key.isReadable(),key.isWritable(),key.channel());

        BufferUtil.clearToFill(buffer);
        read = client.read(buffer);
        BufferUtil.flipToFlush(buffer,0);
        System.err.printf("client read=%d '%s'%n",read,BufferUtil.toString(buffer));


        server.close();

        selected = clientSelector.selectNow();
        System.err.printf("clientSelected=%d %s%n",selected,clientSelector.selectedKeys());
        key = clientSelector.selectedKeys().iterator().next();
        clientSelector.selectedKeys().clear();
        System.err.printf("key=%s/%s c=%b a=%b r=%b w=%b ch=%s%n",key,key.attachment(),key.isConnectable(),key.isAcceptable(),key.isReadable(),key.isWritable(),key.channel());

        BufferUtil.clearToFill(buffer);
        read = client.read(buffer);
        BufferUtil.flipToFlush(buffer,0);
        System.err.printf("client read=%d '%s'%n",read,BufferUtil.toString(buffer));

                
    }
    
}
