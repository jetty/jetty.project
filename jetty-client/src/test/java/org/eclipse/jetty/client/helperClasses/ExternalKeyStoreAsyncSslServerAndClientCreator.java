package org.eclipse.jetty.client.helperClasses;

import java.io.FileInputStream;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;

public class ExternalKeyStoreAsyncSslServerAndClientCreator extends AbstractSslServerAndClientCreator implements ServerAndClientCreator
{

    public HttpClient createClient(long idleTimeout, long timeout, int connectTimeout) throws Exception
    {
        HttpClient httpClient = new HttpClient();
        httpClient.setConnectorType(HttpClient.CONNECTOR_SELECT_CHANNEL);
        httpClient.setMaxConnectionsPerAddress(2);

        String keystore = MavenTestingUtils.getTestResourceFile("keystore").getAbsolutePath();

        httpClient.setKeyStoreInputStream(new FileInputStream(keystore));
        httpClient.setKeyStorePassword("storepwd");
        httpClient.setKeyManagerPassword("keypwd");
        httpClient.start();
        return httpClient;
    }


}
