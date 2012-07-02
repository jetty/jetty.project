package org.eclipse.jetty.websocket.client;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.URI;
import java.nio.channels.SocketChannel;
import java.util.Map;

import org.eclipse.jetty.util.FutureCallback;
import org.eclipse.jetty.websocket.api.WebSocketConnection;
import org.eclipse.jetty.websocket.api.WebSocketEventDriver;
import org.eclipse.jetty.websocket.api.WebSocketPolicy;

public class WebSocketClient
{
    public static class ConnectFuture extends FutureCallback<WebSocketConnection>
    {
        private final WebSocketClient client;
        private final URI websocketUri;
        private final WebSocketEventDriver websocket;

        public ConnectFuture(WebSocketClient client, URI websocketUri, WebSocketEventDriver websocket)
        {
            this.client = client;
            this.websocketUri = websocketUri;
            this.websocket = websocket;
        }

        @Override
        public void completed(WebSocketConnection context)
        {
            // TODO Auto-generated method stub
            super.completed(context);
        }

        @Override
        public void failed(WebSocketConnection context, Throwable cause)
        {
            // TODO Auto-generated method stub
            super.failed(context,cause);
        }

        public WebSocketClient getClient()
        {
            return client;
        }

        public Map<String, String> getCookies()
        {
            // TODO Auto-generated method stub
            return null;
        }

        public WebSocketClientFactory getFactory()
        {
            return client.factory;
        }

        public String getOrigin()
        {
            // TODO Auto-generated method stub
            return null;
        }

        public WebSocketEventDriver getWebSocket()
        {
            return websocket;
        }

        public URI getWebSocketUri()
        {
            return websocketUri;
        }
    }

    private final WebSocketClientFactory factory;

    private SocketAddress bindAddress;
    private WebSocketPolicy policy;

    public WebSocketClient(WebSocketClientFactory factory)
    {
        this.factory = factory;
        this.policy = WebSocketPolicy.newClientPolicy();
    }

    public FutureCallback<WebSocketConnection> connect(URI websocketUri, Object websocketPojo) throws IOException
    {
        if (!factory.isStarted())
        {
            throw new IllegalStateException(WebSocketClientFactory.class.getSimpleName() + " is not started");
        }

        SocketChannel channel = SocketChannel.open();
        if (bindAddress != null)
        {
            channel.bind(bindAddress);
        }
        channel.socket().setTcpNoDelay(true);
        channel.configureBlocking(false);

        InetSocketAddress address = new InetSocketAddress(websocketUri.getHost(),websocketUri.getPort());

        WebSocketEventDriver websocket = this.factory.newWebSocketDriver(websocketPojo);
        ConnectFuture result = new ConnectFuture(this,websocketUri,websocket);

        channel.connect(address);
        factory.getSelector().connect(channel,result);

        return result;
    }

    /**
     * @return the address to bind the socket channel to
     * @see #setBindAddress(SocketAddress)
     */
    public SocketAddress getBindAddress()
    {
        return bindAddress;
    }

    public WebSocketPolicy getPolicy()
    {
        return this.policy;
    }

    /**
     * @param bindAddress
     *            the address to bind the socket channel to
     * @see #getBindAddress()
     */
    public void setBindAddress(SocketAddress bindAddress)
    {
        this.bindAddress = bindAddress;
    }
}
