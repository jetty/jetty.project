package org.eclipse.jetty.client;

import org.eclipse.jetty.server.ssl.SslSelectChannelConnector;

public class SslSelectChannelValidationTest extends SslValidationTestBase
{
    static
    {
        __klass = SslSelectChannelConnector.class;
        __konnector = HttpClient.CONNECTOR_SELECT_CHANNEL;
    }
}
