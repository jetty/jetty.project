package org.eclipse.jetty.client;

import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.client.api.Response;

public class HttpConversation
{
    private final HttpSender sender;
    private final HttpReceiver receiver;
    private HttpConnection connection;
    private Request request;
    private Response.Listener listener;
    private HttpResponse response;

    public HttpConversation()
    {
        sender = new HttpSender();
        receiver = new HttpReceiver();
    }

    public void prepare(HttpConnection connection, Request request, Response.Listener listener)
    {
        if (this.connection != null)
            throw new IllegalStateException();
        this.connection = connection;
        this.request = request;
        this.listener = listener;
        this.response = new HttpResponse(request, listener);
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

    public HttpResponse response()
    {
        return response;
    }

    public void send()
    {
        sender.send(this);
    }

    public void idleTimeout()
    {
        receiver.idleTimeout();
    }

    public void receive()
    {
        receiver.receive(this);
    }
}
