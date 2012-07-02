package org.eclipse.jetty.websocket.server;

import java.util.List;

import org.eclipse.jetty.websocket.api.ExtensionConfig;

public interface WebSocketRequest
{
    // TODO: getSession
    // TODO: getCookies
    // TODO: getRequestAttributes ?

    public List<ExtensionConfig> getExtensions();

    public String getHeader(String name);

    public String getHost();

    public String getHttpEndPointName();

    public String getOrigin();

    public List<String> getSubProtocols();

    public boolean hasSubProtocol(String test);

    public boolean isOrigin(String test);
}
