package org.eclipse.jetty.http3.server;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ReadPendingException;
import java.nio.channels.WritePendingException;

import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.util.Callback;

public class DatagramAdaptingEndPoint implements EndPoint
{
    private final ServerDatagramEndPoint delegate;
    private InetSocketAddress remoteAddress;

    public DatagramAdaptingEndPoint(ServerDatagramEndPoint delegate)
    {
        this.delegate = delegate;
    }

    @Override
    public InetSocketAddress getLocalAddress()
    {
        return delegate.getLocalAddress();
    }

    @Override
    public InetSocketAddress getRemoteAddress()
    {
        return remoteAddress;
    }

    @Override
    public boolean isOpen()
    {
        return delegate.isOpen();
    }

    @Override
    public long getCreatedTimeStamp()
    {
        return delegate.getCreatedTimeStamp();
    }

    @Override
    public void shutdownOutput()
    {
        delegate.shutdownOutput();
    }

    @Override
    public boolean isOutputShutdown()
    {
        return delegate.isOutputShutdown();
    }

    @Override
    public boolean isInputShutdown()
    {
        return delegate.isInputShutdown();
    }

    @Override
    public void close(Throwable cause)
    {
        delegate.close(cause);
    }

    @Override
    public int fill(ByteBuffer buffer) throws IOException
    {
        int filled = delegate.fill(buffer);
        if (filled == 0)
            return 0;

        int headerPosition = buffer.position();

        byte[] address;
        byte ipVersion = buffer.get();
        if (ipVersion == 4)
            address = new byte[4];
        else if (ipVersion == 6)
            address = new byte[6];
        else throw new IOException("Unsupported IP version: " + ipVersion);
        buffer.get(address);
        int port = buffer.getChar();
        remoteAddress = new InetSocketAddress(InetAddress.getByAddress(address), port);

        buffer.position(headerPosition + 19);

        return filled;
    }

    @Override
    public boolean flush(ByteBuffer... buffers) throws IOException
    {
        return delegate.flush(buffers);
    }

    @Override
    public void write(Callback callback, ByteBuffer... buffers) throws WritePendingException
    {
        ByteBuffer[] delegateBuffers = new ByteBuffer[buffers.length + 1];
        System.arraycopy(buffers, 0, delegateBuffers, 1, buffers.length);

        delegateBuffers[0] = ByteBuffer.allocate(19);

        byte[] addressBytes = remoteAddress.getAddress().getAddress();
        byte ipVersion;
        if (remoteAddress.getAddress() instanceof Inet4Address)
            ipVersion = 4;
        else if (remoteAddress.getAddress() instanceof Inet6Address)
            ipVersion = 6;
        else throw new IllegalArgumentException("Unsupported address type: " + remoteAddress.getAddress().getClass());
        int port = remoteAddress.getPort();

        delegateBuffers[0].put(ipVersion);
        delegateBuffers[0].put(addressBytes);
        delegateBuffers[0].putChar((char)port);
        delegateBuffers[0].position(0);

        delegate.write(callback, delegateBuffers);
    }

    @Override
    public Object getTransport()
    {
        return delegate.getTransport();
    }

    @Override
    public long getIdleTimeout()
    {
        return delegate.getIdleTimeout();
    }

    @Override
    public void setIdleTimeout(long idleTimeout)
    {
        delegate.setIdleTimeout(idleTimeout);
    }

    @Override
    public void fillInterested(Callback callback) throws ReadPendingException
    {
        delegate.fillInterested(callback);
    }

    @Override
    public boolean tryFillInterested(Callback callback)
    {
        return delegate.tryFillInterested(callback);
    }

    @Override
    public boolean isFillInterested()
    {
        return delegate.isFillInterested();
    }

    @Override
    public Connection getConnection()
    {
        return delegate.getConnection();
    }

    @Override
    public void setConnection(Connection connection)
    {
        delegate.setConnection(connection);
    }

    @Override
    public void onOpen()
    {
        delegate.onOpen();
    }

    @Override
    public void onClose(Throwable cause)
    {
        delegate.onClose(cause);
    }

    @Override
    public void upgrade(Connection newConnection)
    {
        delegate.upgrade(newConnection);
    }
}
