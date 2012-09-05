package org.eclipse.jetty.client;

import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.client.api.Connection;
import org.eclipse.jetty.client.api.ContentProvider;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.client.api.Response;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.io.AbstractConnection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

public class HttpConnection extends AbstractConnection implements Connection
{
    private static final Logger LOG = Log.getLogger(HttpConnection.class);

    private final AtomicReference<HttpExchange> exchange = new AtomicReference<>();
    private final HttpClient client;
    private final HttpDestination destination;
    private final HttpSender sender;
    private final HttpReceiver receiver;

    public HttpConnection(HttpClient client, EndPoint endPoint, HttpDestination destination)
    {
        super(endPoint, client.getExecutor());
        this.client = client;
        this.destination = destination;
        this.sender = new HttpSender(this);
        this.receiver = new HttpReceiver(this);
    }

    public HttpClient getHttpClient()
    {
        return client;
    }

    @Override
    public void onOpen()
    {
        super.onOpen();
        fillInterested();
    }

    @Override
    protected boolean onReadTimeout()
    {
        HttpExchange exchange = this.exchange.get();
        if (exchange != null)
            idleTimeout();

        // We will be closing the connection, so remove it
        LOG.debug("Connection {} idle timeout", this);
        destination.remove(this);

        return true;
    }

    protected void idleTimeout()
    {
        receiver.idleTimeout();
    }

    @Override
    public void send(Request request, Response.Listener listener)
    {
        normalizeRequest(request);
        HttpConversation conversation = client.conversationFor(request);
        HttpExchange exchange = new HttpExchange(conversation, this, request, listener);
        setExchange(exchange);
        conversation.add(exchange);
        sender.send(exchange);
    }

    private void normalizeRequest(Request request)
    {
        if (request.method() == null)
            request.method(HttpMethod.GET);

        if (request.version() == null)
            request.version(HttpVersion.HTTP_1_1);

        if (request.agent() == null)
            request.agent(client.getUserAgent());

        if (request.idleTimeout() <= 0)
            request.idleTimeout(client.getIdleTimeout());

        // TODO: follow redirects
        // TODO: cookies

        HttpVersion version = request.version();
        HttpFields headers = request.headers();
        ContentProvider content = request.content();

        // Make sure the path is there
        String path = request.path();
        if (path.matches("\\s*"))
            request.path("/");

        // Add content headers
        if (content != null)
        {
            long contentLength = content.length();
            if (contentLength >= 0)
            {
                if (!headers.containsKey(HttpHeader.CONTENT_LENGTH.asString()))
                    headers.put(HttpHeader.CONTENT_LENGTH, String.valueOf(contentLength));
            }
            else
            {
                if (!headers.containsKey(HttpHeader.TRANSFER_ENCODING.asString()))
                    headers.put(HttpHeader.TRANSFER_ENCODING, "chunked");
            }
        }

        // TODO: decoder headers

        // If we are HTTP 1.1, add the Host header
        if (version.getVersion() > 10)
        {
            if (!headers.containsKey(HttpHeader.HOST.asString()))
            {
                String value = request.host();
                int port = request.port();
                if (port > 0)
                    value += ":" + port;
                headers.put(HttpHeader.HOST, value);
            }
        }
    }

    public HttpExchange getExchange()
    {
        return exchange.get();
    }

    protected void setExchange(HttpExchange exchange)
    {
        if (!this.exchange.compareAndSet(null, exchange))
            throw new UnsupportedOperationException("Pipelined requests not supported");
        else
            LOG.debug("{} associated to {}", exchange, this);
    }

    @Override
    public void onFillable()
    {
        HttpExchange exchange = getExchange();
        if (exchange != null)
            exchange.receive();
        else
            throw new IllegalStateException();
    }

    protected void receive()
    {
        receiver.receive();
    }

    public void completed(HttpExchange exchange, boolean success)
    {
        if (this.exchange.compareAndSet(exchange, null))
        {
            LOG.debug("{} disassociated from {}", exchange, this);
            if (success)
            {
                destination.release(this);
            }
            else
            {
                destination.remove(this);
                close();
            }
        }
        else
        {
            destination.remove(this);
            close();
            throw new IllegalStateException();
        }
    }

    @Override
    public String toString()
    {
        return String.format("%s@%x(l:%s <-> r:%s)",
                HttpConnection.class.getSimpleName(),
                hashCode(),
                getEndPoint().getLocalAddress(),
                getEndPoint().getRemoteAddress());
    }
}
