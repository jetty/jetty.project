package org.eclipse.jetty.client;

import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.client.api.Response;

public class HttpExchange
{
    private final HttpConversation conversation;
    private final HttpSender sender;
    private final HttpReceiver receiver;
    private final Request request;
    private final Response.Listener listener;
    private final HttpResponse response;

    public HttpExchange(HttpConversation conversation, HttpSender sender, HttpReceiver receiver, Request request, Response.Listener listener)
    {
        this.conversation = conversation;
        this.sender = sender;
        this.receiver = receiver;
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

    public void requestDone(boolean success)
    {
        // TODO
    }

    public void responseDone(boolean success)
    {
        // TODO
    }
}
