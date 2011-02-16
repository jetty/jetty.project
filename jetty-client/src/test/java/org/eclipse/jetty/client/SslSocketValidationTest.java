package org.eclipse.jetty.client;

import org.eclipse.jetty.server.ssl.SslSocketConnector;

public class SslSocketValidationTest extends SslValidationTestBase
{
    static
    {
        __klass = SslSocketConnector.class;
        __konnector = HttpClient.CONNECTOR_SOCKET;
    }
}
