package org.eclipse.jetty.websocket.jsr356.server.internal;

import java.util.List;

import org.eclipse.jetty.websocket.core.ExtensionConfig;
import org.eclipse.jetty.websocket.jsr356.UpgradeResponse;
import org.eclipse.jetty.websocket.servlet.ServletUpgradeResponse;

public class UpgradeResponseAdapter implements UpgradeResponse
{
    private final ServletUpgradeResponse servletResponse;

    public UpgradeResponseAdapter(ServletUpgradeResponse servletResponse)
    {
        this.servletResponse = servletResponse;
    }

    @Override
    public String getAcceptedSubProtocol()
    {
        return this.servletResponse.getAcceptedSubProtocol();
    }

    @Override
    public List<ExtensionConfig> getExtensions()
    {
        return this.servletResponse.getExtensions();
    }
}
