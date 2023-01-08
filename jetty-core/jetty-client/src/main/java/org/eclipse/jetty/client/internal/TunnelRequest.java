package org.eclipse.jetty.client.internal;

import java.net.URI;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.Origin;

public class TunnelRequest extends HttpRequest
{
    public TunnelRequest(HttpClient client, Origin.Address address)
    {
        super(client, new HttpConversation(), URI.create("http://" + address.asString()));
    }
}
