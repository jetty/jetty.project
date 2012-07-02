package org.eclipse.jetty.websocket.client.io;

import java.io.IOException;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLException;

import org.eclipse.jetty.io.AsyncConnection;
import org.eclipse.jetty.io.AsyncEndPoint;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.SelectChannelEndPoint;
import org.eclipse.jetty.io.SelectorManager;
import org.eclipse.jetty.io.ssl.SslConnection;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.websocket.api.WebSocketEventDriver;
import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.client.WebSocketClient;
import org.eclipse.jetty.websocket.client.WebSocketClientFactory;
import org.eclipse.jetty.websocket.io.WebSocketAsyncConnection;

public class WebSocketClientSelectorManager extends SelectorManager
{
    private SslContextFactory sslContextFactory;
    private Executor executor;
    private ByteBufferPool bufferPool;

    public WebSocketClientSelectorManager(ByteBufferPool bufferPool, Executor executor)
    {
        super();
        this.bufferPool = bufferPool;
        this.executor = executor;
    }

    @Override
    protected void endPointClosed(AsyncEndPoint endpoint)
    {
        endpoint.getAsyncConnection().onClose();
    }

    @Override
    protected void endPointOpened(AsyncEndPoint endpoint)
    {
    }

    @Override
    protected void endPointUpgraded(AsyncEndPoint endpoint, AsyncConnection oldConnection)
    {
        // TODO Investigate role of this with websocket

    }

    @Override
    protected void execute(Runnable task)
    {
        // TODO Auto-generated method stub
    }

    @Override
    protected int getMaxIdleTime()
    {
        return 0;
    }

    public SslContextFactory getSslContextFactory()
    {
        return sslContextFactory;
    }

    public AsyncConnection newAsyncConnection(SocketChannel channel, AsyncEndPoint endPoint, Object attachment)
    {
        WebSocketClient.ConnectFuture confut = (WebSocketClient.ConnectFuture)attachment;
        WebSocketClientFactory factory = confut.getFactory();
        WebSocketEventDriver websocket = confut.getWebSocket();

        Executor executor = factory.getExecutor();
        WebSocketPolicy policy = factory.getPolicy();
        ByteBufferPool bufferPool = factory.getBufferPool();

        WebSocketAsyncConnection connection = new WebSocketAsyncConnection(endPoint,executor,policy,bufferPool);
        endPoint.setAsyncConnection(connection);
        connection.getParser().addListener(websocket);

        // TODO: track open websockets? bind open websocket to connection?

        return connection;
    }

    @Override
    public AsyncConnection newConnection(SocketChannel channel, AsyncEndPoint endPoint, Object attachment)
    {
        WebSocketClient.ConnectFuture confut = (WebSocketClient.ConnectFuture)attachment;

        try
        {
            String scheme = confut.getWebSocketUri().getScheme();

            if ((sslContextFactory != null) && ("wss".equalsIgnoreCase(scheme)))
            {
                final AtomicReference<AsyncEndPoint> sslEndPointRef = new AtomicReference<>();
                final AtomicReference<Object> attachmentRef = new AtomicReference<>(attachment);
                SSLEngine engine = newSSLEngine(sslContextFactory,channel);
                SslConnection sslConnection = new SslConnection(bufferPool,executor,endPoint,engine)
                {
                    @Override
                    public void onClose()
                    {
                        sslEndPointRef.set(null);
                        attachmentRef.set(null);
                        super.onClose();
                    }
                };
                endPoint.setAsyncConnection(sslConnection);
                AsyncEndPoint sslEndPoint = sslConnection.getSslEndPoint();
                sslEndPointRef.set(sslEndPoint);

                startHandshake(engine);

                AsyncConnection connection = newAsyncConnection(channel,sslEndPoint,attachment);
                endPoint.setAsyncConnection(connection);
                return connection;
            }
            else
            {
                AsyncConnection connection = newAsyncConnection(channel,endPoint,attachment);
                endPoint.setAsyncConnection(connection);
                return connection;
            }
        }
        catch (Throwable t)
        {
            LOG.debug(t);
            confut.failed(null,t);
            throw t;
        }
    }

    @Override
    protected SelectChannelEndPoint newEndPoint(SocketChannel channel, ManagedSelector selectSet, SelectionKey key) throws IOException
    {
        SelectChannelEndPoint endp = new SelectChannelEndPoint(channel,selectSet,key,getMaxIdleTime());
        endp.setAsyncConnection(selectSet.getManager().newConnection(channel,endp,key.attachment()));
        return endp;
    }

    public SSLEngine newSSLEngine(SslContextFactory sslContextFactory, SocketChannel channel)
    {
        String peerHost = channel.socket().getInetAddress().getHostAddress();
        int peerPort = channel.socket().getPort();
        SSLEngine engine = sslContextFactory.newSslEngine(peerHost,peerPort);
        engine.setUseClientMode(true);
        return engine;
    }

    public void setSslContextFactory(SslContextFactory sslContextFactory)
    {
        this.sslContextFactory = sslContextFactory;
    }

    private void startHandshake(SSLEngine engine)
    {
        try
        {
            engine.beginHandshake();
        }
        catch (SSLException x)
        {
            throw new RuntimeException(x);
        }
    }
}
