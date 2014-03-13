package org.eclipse.jetty.fcgi.client.http;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.HttpExchange;
import org.eclipse.jetty.client.Origin;
import org.eclipse.jetty.client.PoolingHttpDestination;

public class HttpDestinationOverFCGI extends PoolingHttpDestination<HttpConnectionOverFCGI>
{
    public HttpDestinationOverFCGI(HttpClient client, Origin origin)
    {
        super(client, origin);
    }

    @Override
    protected void send(HttpConnectionOverFCGI connection, HttpExchange exchange)
    {
        connection.send(exchange);
    }
}
