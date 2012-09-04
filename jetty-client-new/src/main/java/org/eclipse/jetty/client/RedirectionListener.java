package org.eclipse.jetty.client;

import java.net.URI;

import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.client.api.Response;

public class RedirectionListener extends Response.Listener.Adapter
{
    private final HttpConnection connection;

    public RedirectionListener(HttpConnection connection)
    {
        this.connection = connection;
    }

    @Override
    public void onSuccess(Response response)
    {
        switch (response.status())
        {
            case 301: // GET or HEAD only allowed, keep the method
            {
                break;
            }
            case 302:
            case 303: // use GET for next request
            {
                String location = response.headers().get("location");
                HttpClient httpClient = connection.getHttpClient();
                Request redirect = httpClient.newRequest(response.request().id(), URI.create(location));
                redirect.send(this);
            }
        }
    }

    @Override
    public void onFailure(Response response, Throwable failure)
    {
         // TODO
    }
}
