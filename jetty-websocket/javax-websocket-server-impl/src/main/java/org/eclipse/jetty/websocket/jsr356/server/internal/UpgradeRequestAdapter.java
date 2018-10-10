package org.eclipse.jetty.websocket.jsr356.server.internal;

import java.net.SocketAddress;
import java.net.URI;
import java.util.List;
import java.util.Map;

import org.eclipse.jetty.websocket.jsr356.UpgradeRequest;
import org.eclipse.jetty.websocket.servlet.ServletUpgradeRequest;

public class UpgradeRequestAdapter implements UpgradeRequest
{
    private final ServletUpgradeRequest servletRequest;

    public UpgradeRequestAdapter(ServletUpgradeRequest servletRequest)
    {
        this.servletRequest = servletRequest;
    }

    @Override
    public SocketAddress getLocalSocketAddress()
    {
        return this.servletRequest.getLocalSocketAddress();
    }

    @Override
    public Map<String, List<String>> getParameterMap()
    {
        return this.servletRequest.getParameterMap();
    }

    @Override
    public String getProtocolVersion()
    {
        return this.servletRequest.getProtocolVersion();
    }

    @Override
    public SocketAddress getRemoteSocketAddress()
    {
        return this.servletRequest.getRemoteSocketAddress();
    }

    @Override
    public URI getRequestURI()
    {
        return this.servletRequest.getRequestURI();
    }

    @Override
    public boolean isSecure()
    {
        return this.servletRequest.isSecure();
    }
}
