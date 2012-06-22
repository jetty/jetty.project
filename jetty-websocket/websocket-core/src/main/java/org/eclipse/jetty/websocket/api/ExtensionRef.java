package org.eclipse.jetty.websocket.api;

import java.util.Map;

/**
 * Proposed interface for API (not yet settled)
 */
public interface ExtensionRef
{
    String getName();

    Map<String, String> getParameters();
}
