package org.eclipse.jetty.websocket.jsr356;

import java.util.List;

import org.eclipse.jetty.websocket.core.ExtensionConfig;

public interface UpgradeResponse
{
    String getAcceptedSubProtocol();

    List<ExtensionConfig> getExtensions();
}
