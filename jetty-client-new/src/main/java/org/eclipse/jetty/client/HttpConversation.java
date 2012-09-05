package org.eclipse.jetty.client;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.client.api.Response;

public class HttpConversation
{
    private final Queue<HttpExchange> exchanges = new ConcurrentLinkedQueue<>();
    private final AtomicReference<Response.Listener> listener = new AtomicReference<>();
    private final HttpClient client;
    private final long id;

    public HttpConversation(HttpClient client, long id)
    {
        this.client = client;
        this.id = id;
    }

    public long id()
    {
        return id;
    }

    public Response.Listener listener()
    {
        return listener.get();
    }

    public void listener(Response.Listener listener)
    {
        this.listener.set(listener);
    }

    public void add(HttpExchange exchange)
    {
        exchanges.offer(exchange);
    }

    public HttpExchange first()
    {
        return exchanges.peek();
    }

    public void complete()
    {
        listener.set(null);
        client.removeConversation(this);
    }

    @Override
    public String toString()
    {
        return String.format("%s[%d]", HttpConversation.class.getSimpleName(), id);
    }
}
