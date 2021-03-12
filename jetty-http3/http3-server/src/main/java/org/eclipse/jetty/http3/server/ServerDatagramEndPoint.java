package org.eclipse.jetty.http3.server;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ReadPendingException;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.WritePendingException;

import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.ManagedSelector;
import org.eclipse.jetty.util.Callback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ServerDatagramEndPoint implements EndPoint
{
    private static final Logger LOG = LoggerFactory.getLogger(ServerDatagramEndPoint.class);

    public ServerDatagramEndPoint(SocketAddress localAddress, SocketAddress remoteAddress, ByteBuffer buffer, SelectableChannel channel)
    {

    }

    public void init(ManagedSelector selector, SelectionKey selectionKey)
    {

    }

    public void onData(ByteBuffer buffer)
    {

    }

    @Override
    public InetSocketAddress getLocalAddress()
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public InetSocketAddress getRemoteAddress()
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean isOpen()
    {
        return false;
    }

    @Override
    public long getCreatedTimeStamp()
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public void shutdownOutput()
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean isOutputShutdown()
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean isInputShutdown()
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public void close(Throwable cause)
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public int fill(ByteBuffer buffer) throws IOException
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean flush(ByteBuffer... buffer) throws IOException
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public Object getTransport()
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public long getIdleTimeout()
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public void setIdleTimeout(long idleTimeout)
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public void fillInterested(Callback callback) throws ReadPendingException
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean tryFillInterested(Callback callback)
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean isFillInterested()
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public void write(Callback callback, ByteBuffer... buffers) throws WritePendingException
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public Connection getConnection()
    {
        return connection;
    }

    private Connection connection;

    @Override
    public void setConnection(Connection connection)
    {
        this.connection = connection;
    }

    @Override
    public void onOpen()
    {
        LOG.info("onOpen");
    }

    @Override
    public void onClose(Throwable cause)
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public void upgrade(Connection newConnection)
    {
        throw new UnsupportedOperationException();
    }
}
