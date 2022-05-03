//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package examples;

import java.lang.management.ManagementFactory;
import java.net.URI;
import java.util.concurrent.TimeUnit;

import jakarta.websocket.ClientEndpointConfig;
import jakarta.websocket.WebSocketContainer;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.http.HttpClientTransportOverHTTP;
import org.eclipse.jetty.ee9.websocket.jakarta.client.JakartaWebSocketClientContainerProvider;
import org.eclipse.jetty.io.ClientConnector;
import org.eclipse.jetty.jmx.MBeanContainer;
import org.eclipse.jetty.util.component.Container;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.util.ssl.SslContextFactory;

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
            /* Since jakarta.websocket clients have no defined LifeCycle we
             * want to either close/stop the client, or exit the JVM
             * via a System.exit(), otherwise the threads this client keeps
             * open will prevent the JVM from terminating naturally.
             * @see https://github.com/eclipse-ee4j/websocket-api/issues/212
             */
            LifeCycle.stop(client);
        }
    }

    /**
     * Since jakarta.websocket does not have an API for configuring SSL, each implementation
     * of jakarta.websocket has to come up with their own SSL configuration mechanism.
     *
     * @return the client WebSocketContainer
     * @see <a href="https://github.com/eclipse-ee4j/websocket-api/issues/210">jakarta.websocket issue #210</a>
     */
    public static WebSocketContainer getConfiguredWebSocketContainer() throws Exception
    {
        SslContextFactory.Client ssl = new SslContextFactory.Client();
        ssl.setExcludeCipherSuites(); // echo.websocket.org use WEAK cipher suites
        ClientConnector clientConnector = new ClientConnector();
        clientConnector.setSslContextFactory(ssl);

        HttpClient httpClient = new HttpClient(new HttpClientTransportOverHTTP(clientConnector));
        WebSocketContainer clientContainer = JakartaWebSocketClientContainerProvider.getContainer(httpClient);

        // Components can be added as a bean to the WebSocketContainer with the Container static method.
        MBeanContainer mbeanContainer = new MBeanContainer(ManagementFactory.getPlatformMBeanServer());
        Container.addBean(clientContainer, mbeanContainer);

        return clientContainer;
    }
}
