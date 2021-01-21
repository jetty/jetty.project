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

import java.io.FileNotFoundException;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.concurrent.TimeUnit;
import javax.websocket.ClientEndpointConfig;
import javax.websocket.ContainerProvider;
import javax.websocket.WebSocketContainer;

import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.util.thread.ThreadClassLoaderScope;

public class SecureWebSocketContainerExample
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
     * <p>
     * When the call to {@link javax.websocket.ContainerProvider}.{@link ContainerProvider#getWebSocketContainer()}
     * occurs, that needs to have a started and available WebSocket Client.
     * Jetty's {@code WebSocketClient} must have a Jetty {@code HttpClient} started as well.
     * If you want SSL, then that configuration has to be passed into the Jetty {@code HttpClient} at initialization.
     * </p>
     * <p>
     * How Jetty makes this available, is via the {@code jetty-websocket-httpclient.xml} classloader resource
     * along with the jetty-xml artifact.
     * </p>
     * <p>
     * This method will look for the file in the classloader resources, and then
     * sets up a {@link URLClassLoader} to make that {@code jetty-websocket-httpclient.xml} available
     * for this specific example.
     * If we had put the `jetty-websocket-httpclient.xml` in the root of a JAR file loaded by this
     * project then you can skip all of the classloader trickery this method performs.
     * </p>
     *
     * @return the client WebSocketContainer
     * @see <a href="https://github.com/eclipse-ee4j/websocket-api/issues/210">javax.websocket issue #210</a>
     */
    public static WebSocketContainer getConfiguredWebSocketContainer() throws Exception
    {
        URL jettyHttpClientConfigUrl = Thread.currentThread().getContextClassLoader()
            .getResource("examples/jetty-websocket-httpclient.xml");

        if (jettyHttpClientConfigUrl == null)
        {
            throw new FileNotFoundException("Unable to find Jetty HttpClient configuration XML");
        }

        URI jettyConfigDirUri = jettyHttpClientConfigUrl.toURI().resolve("./");

        ClassLoader parentClassLoader = Thread.currentThread().getContextClassLoader();
        URL[] urls = new URL[]{
            jettyConfigDirUri.toURL()
        };
        URLClassLoader classLoader = new URLClassLoader(urls, parentClassLoader);

        try (ThreadClassLoaderScope ignore = new ThreadClassLoaderScope(classLoader))
        {
            return ContainerProvider.getWebSocketContainer();
        }
    }
}
