//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package examples;

import java.net.URI;
import java.util.concurrent.TimeUnit;
import javax.websocket.ClientEndpointConfig;
import javax.websocket.WebSocketContainer;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.websocket.jsr356.ClientContainer;

public class SecureClientContainerExample
{
    public static void main(String[] args)
    {
        WebSocketContainer client = null;
        try
        {
            URI echoUri = URI.create("wss://echo.websocket.org");
            client = getConfiguredWebSocketContainer();

            ClientEndpointConfig clientEndpointConfig = ClientEndpointConfig.Builder.create()
                .configurator(new OriginServerConfigurator("https://websocket.org"))
                .build();
            EchoEndpoint echoEndpoint = new EchoEndpoint();
            client.connectToServer(echoEndpoint, clientEndpointConfig, echoUri);
            System.out.printf("Connecting to : %s%n", echoUri);

            // wait for closed socket connection.
            echoEndpoint.awaitClose(5, TimeUnit.SECONDS);
        }
        catch (Throwable t)
        {
            t.printStackTrace();
        }
        finally
        {
            /* Since javax.websocket clients have no defined LifeCycle we
             * want to either close/stop the client, or exit the JVM
             * via a System.exit(), otherwise the threads this client keeps
             * open will prevent the JVM from terminating naturally.
             * @see https://github.com/eclipse-ee4j/websocket-api/issues/212
             */
            LifeCycle.stop(client);
        }
    }

    /**
     * Since javax.websocket does not have an API for configuring SSL, each implementation
     * of javax.websocket has to come up with their own SSL configuration mechanism.
     *
     * @return the client WebSocketContainer
     * @see <a href="https://github.com/eclipse-ee4j/websocket-api/issues/210">javax.websocket issue #210</a>
     */
    public static WebSocketContainer getConfiguredWebSocketContainer() throws Exception
    {
        SslContextFactory ssl = new SslContextFactory.Client();
        ssl.setExcludeCipherSuites(); // echo.websocket.org use WEAK cipher suites
        HttpClient httpClient = new HttpClient(ssl);
        ClientContainer clientContainer = new ClientContainer(httpClient);
        clientContainer.getClient().addManaged(httpClient); // allow clientContainer to own httpClient (for start/stop lifecycle)
        clientContainer.start();
        return clientContainer;
    }
}
