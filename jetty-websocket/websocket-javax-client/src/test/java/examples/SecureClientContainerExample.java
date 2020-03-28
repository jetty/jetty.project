//
// ========================================================================
// Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under
// the terms of the Eclipse Public License 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0
//
// This Source Code may also be made available under the following
// Secondary Licenses when the conditions for such availability set
// forth in the Eclipse Public License, v. 2.0 are satisfied:
// the Apache License v2.0 which is available at
// https://www.apache.org/licenses/LICENSE-2.0
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package examples;

import java.net.URI;
import java.util.concurrent.TimeUnit;
import javax.websocket.ClientEndpointConfig;
import javax.websocket.WebSocketContainer;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.http.HttpClientTransportOverHTTP;
import org.eclipse.jetty.io.ClientConnector;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.websocket.javax.client.internal.JavaxWebSocketClientContainer;

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
        SslContextFactory.Client ssl = new SslContextFactory.Client();
        ssl.setExcludeCipherSuites(); // echo.websocket.org use WEAK cipher suites
        ClientConnector clientConnector = new ClientConnector();
        clientConnector.setSslContextFactory(ssl);

        HttpClient httpClient = new HttpClient(new HttpClientTransportOverHTTP(clientConnector));
        JavaxWebSocketClientContainer clientContainer = new JavaxWebSocketClientContainer(httpClient);
        clientContainer.addManaged(httpClient); // allow clientContainer to own httpClient (for start/stop lifecycle)
        clientContainer.start();
        return clientContainer;
    }
}
