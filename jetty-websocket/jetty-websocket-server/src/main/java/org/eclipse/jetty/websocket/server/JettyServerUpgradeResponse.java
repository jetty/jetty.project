package org.eclipse.jetty.websocket.server;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.eclipse.jetty.websocket.api.extensions.ExtensionConfig;
import org.eclipse.jetty.websocket.common.JettyExtensionConfig;
import org.eclipse.jetty.websocket.servlet.ServletUpgradeResponse;

public class JettyServerUpgradeResponse
{
    private ServletUpgradeResponse upgradeResponse;

    public JettyServerUpgradeResponse(ServletUpgradeResponse response)
    {
        upgradeResponse = response;
    }

    public void addHeader(String name, String value)
    {
        upgradeResponse.addHeader(name, value);
    }

    public void setHeader(String name, String value)
    {
        upgradeResponse.setHeader(name, value);
    }

    public void setHeader(String name, List<String> values)
    {
        upgradeResponse.setHeader(name, values);
    }

    public String getAcceptedSubProtocol()
    {
        return upgradeResponse.getAcceptedSubProtocol();
    }

    public List<ExtensionConfig> getExtensions()
    {
        return upgradeResponse.getExtensions().stream().map(JettyExtensionConfig::new).collect(Collectors.toList());
    }

    public String getHeader(String name)
    {
        return upgradeResponse.getHeader(name);
    }

    public Set<String> getHeaderNames()
    {
        return upgradeResponse.getHeaderNames();
    }

    public Map<String, List<String>> getHeadersMap()
    {
        return upgradeResponse.getHeadersMap();
    }

    public List<String> getHeaders(String name)
    {
        return upgradeResponse.getHeaders(name);
    }

    public int getStatusCode()
    {
        return upgradeResponse.getStatusCode();
    }

    public boolean isCommitted()
    {
        return upgradeResponse.isCommitted();
    }

    public void sendError(int statusCode, String message) throws IOException
    {
        upgradeResponse.sendError(statusCode, message);
    }

    public void sendForbidden(String message) throws IOException
    {
        upgradeResponse.sendForbidden(message);
    }

    public void setAcceptedSubProtocol(String protocol)
    {
        upgradeResponse.setAcceptedSubProtocol(protocol);
    }

    public void setExtensions(List<ExtensionConfig> configs)
    {
        upgradeResponse.setExtensions(configs.stream()
                .map(c->new org.eclipse.jetty.websocket.core.ExtensionConfig(c.getName(), c.getParameters()))
                .collect(Collectors.toList()));
    }

    public void setStatusCode(int statusCode)
    {
        upgradeResponse.setStatusCode(statusCode);
    }
}
