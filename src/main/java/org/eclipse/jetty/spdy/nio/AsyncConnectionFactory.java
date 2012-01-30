package org.eclipse.jetty.spdy.nio;

import java.nio.channels.SocketChannel;

import org.eclipse.jetty.io.AsyncEndPoint;
import org.eclipse.jetty.io.nio.AsyncConnection;

public interface AsyncConnectionFactory
{
    public String getProtocol();

    public AsyncConnection newAsyncConnection(SocketChannel channel, AsyncEndPoint endPoint, Object attachment);
}
