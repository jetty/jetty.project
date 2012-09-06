package org.eclipse.jetty.client;

import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.client.api.Response;
import org.eclipse.jetty.client.util.BufferingResponseListener;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpVersion;

public class HttpContentResponse implements ContentResponse
{
    private final Response response;
    private final BufferingResponseListener listener;

    public HttpContentResponse(Response response, BufferingResponseListener listener)
    {
        this.response = response;
        this.listener = listener;
    }

    @Override
    public Request request()
    {
        return response.request();
    }

    @Override
    public Listener listener()
    {
        return response.listener();
    }

    @Override
    public HttpVersion version()
    {
        return response.version();
    }

    @Override
    public int status()
    {
        return response.status();
    }

    @Override
    public String reason()
    {
        return response.reason();
    }

    @Override
    public HttpFields headers()
    {
        return response.headers();
    }

    @Override
    public void abort()
    {
        response.abort();
    }

    @Override
    public byte[] content()
    {
        return listener.content();
    }
}
