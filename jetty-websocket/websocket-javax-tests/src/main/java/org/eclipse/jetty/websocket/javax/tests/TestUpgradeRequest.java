package org.eclipse.jetty.websocket.javax.tests;

import java.net.URI;
import java.security.Principal;

import org.eclipse.jetty.websocket.javax.common.UpgradeRequest;

public class TestUpgradeRequest implements UpgradeRequest
{
    private final URI requestURI;

    public TestUpgradeRequest()
    {
        /* anonymous, no requestURI, upgrade request */
        this(null);
    }

    public TestUpgradeRequest(URI uri)
    {
        this.requestURI = uri;
    }

    @Override
    public Principal getUserPrincipal()
    {
        return null;
    }

    @Override
    public URI getRequestURI()
    {
        return requestURI;
    }
}
