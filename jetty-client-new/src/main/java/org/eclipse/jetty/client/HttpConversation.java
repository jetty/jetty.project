package org.eclipse.jetty.client;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.client.api.Response;

public class HttpConversation
{
    private final List<HttpExchange> exchanges = new ArrayList<>();
    private final HttpClient client;
    private final long id;

    private HttpConnection connection;
    private Request request;
    private Response.Listener listener;

    public HttpConversation(HttpClient client, long id)
    {
        this.client = client;
        this.id = id;
    }

    public void done()
    {
        reset();
    }

    private void reset()
    {
        connection = null;
        request = null;
        listener = null;
    }

    public HttpConnection connection()
    {
        return connection;
    }

    public Request request()
    {
        return request;
    }

    public Response.Listener listener()
    {
        return listener;
    }

    public void listener(Response.Listener listener)
    {
        this.listener = listener;
    }

    public void add(HttpExchange exchange)
    {
        exchanges.add(exchange);
    }

    public HttpExchange first()
    {
        return exchanges.get(0);
    }

    @Override
    public String toString()
    {
        return String.format("%s[%d]", HttpConversation.class.getSimpleName(), id);
    }
}
