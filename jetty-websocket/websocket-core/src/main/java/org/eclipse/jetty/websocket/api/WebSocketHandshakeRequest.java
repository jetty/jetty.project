package org.eclipse.jetty.websocket.api;

import java.util.List;

/**
 * Proposed interface for API (not yet settled)
 */
public interface WebSocketHandshakeRequest
{
    // get/set arbitrary Http Header Fields? (if so, then this should not use servlet-api)

    String getEndpoint();

    List<ExtensionRef> getExtensions();

    String getHost();

    String getOrigin();

    String getWebSocketKey();

    String[] getWebSocketProtocols();

    int getWebSocketVersion();
}
