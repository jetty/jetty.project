package org.eclipse.jetty.client.helperClasses;

import org.eclipse.jetty.client.HttpClient;

public class SslServerAndClientCreator extends AbstractSslServerAndClientCreator implements ServerAndClientCreator
{

    public HttpClient createClient(long idleTimeout, long timeout, int connectTimeout) throws Exception
    {
        HttpClient httpClient = new HttpClient();
        httpClient.setIdleTimeout(idleTimeout);
        httpClient.setTimeout(timeout);
        httpClient.setConnectTimeout(connectTimeout);
        httpClient.setConnectorType(HttpClient.CONNECTOR_SOCKET);
        httpClient.setMaxConnectionsPerAddress(2);
        httpClient.start();
        return httpClient;
    }
}
