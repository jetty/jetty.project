package org.eclipse.jetty.websocket.jsr356;

import java.net.SocketAddress;
import java.net.URI;
import java.util.List;
import java.util.Map;

public interface UpgradeRequest
{
    SocketAddress getLocalSocketAddress();

    Map<String, List<String>> getParameterMap();

    String getProtocolVersion();

    SocketAddress getRemoteSocketAddress();

    URI getRequestURI();

    boolean isSecure();
}
