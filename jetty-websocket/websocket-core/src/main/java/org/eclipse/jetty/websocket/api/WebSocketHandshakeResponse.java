package org.eclipse.jetty.websocket.api;

import java.util.List;

/**
 * Proposed interface for API (not yet settled)
 */
public interface WebSocketHandshakeResponse
{
    String getWebSocketAccept();

    boolean isUpgradeAccepted();

    void setExtensions(List<ExtensionRef> refs);

    void setUpgradeAccepted(boolean accept);
}
