package org.eclipse.jetty.client;

import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.client.api.Response;

public class HttpExchange
{
    private static final int REQUEST_SUCCESS = 1;
    private static final int RESPONSE_SUCCESS = 2;
    private static final int REQUEST_RESPONSE_SUCCESS = REQUEST_SUCCESS + RESPONSE_SUCCESS;

    private final AtomicInteger done = new AtomicInteger();
    private final HttpConversation conversation;
    private final HttpConnection connection;
    private final Request request;
    private final Response.Listener listener;
    private final HttpResponse response;

    public HttpExchange(HttpConversation conversation, HttpConnection connection, Request request, Response.Listener listener)
    {
        this.conversation = conversation;
        this.connection = connection;
        this.request = request;
        this.listener = listener;
        this.response = new HttpResponse(request, listener);
    }

    public HttpConversation conversation()
    {
        return conversation;
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

    public void receive()
    {
        connection.receive();
    }

    public void requestDone(boolean success)
    {
        done(success, REQUEST_SUCCESS);
    }

    public void responseDone(boolean success)
    {
        done(success, RESPONSE_SUCCESS);
    }

    private void done(boolean success, int kind)
    {
        if (success)
        {
            if (done.addAndGet(kind) == REQUEST_RESPONSE_SUCCESS)
            {
                connection.completed(this, true);
            }
        }
        else
        {
            connection.completed(this, false);
        }
    }

    @Override
    public String toString()
    {
        return String.format("%s@%x", HttpExchange.class.getSimpleName(), hashCode());
    }
}
