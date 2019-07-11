//
//  ========================================================================
//  Copyright (c) 1995-2019 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.client;

import java.nio.file.Path;
import java.util.function.Consumer;
import java.util.stream.Stream;

import org.eclipse.jetty.client.http.HttpClientTransportOverHTTP;
import org.eclipse.jetty.http.HttpScheme;
import org.eclipse.jetty.io.ClientConnector;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.util.SocketAddressResolver;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.eclipse.jetty.util.thread.ScheduledExecutorScheduler;
import org.eclipse.jetty.util.thread.Scheduler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.ArgumentsProvider;

public abstract class AbstractHttpClientServerTest
{
    protected Server server;
    protected HttpClient client;
    protected ServerConnector connector;

    public void start(final Scenario scenario, Handler handler) throws Exception
    {
        startServer(scenario, handler);
        startClient(scenario);
    }

    protected void startServer(final Scenario scenario, Handler handler) throws Exception
    {
        if (server == null)
        {
            QueuedThreadPool serverThreads = new QueuedThreadPool();
            serverThreads.setName("server");
            server = new Server(serverThreads);
        }
        connector = new ServerConnector(server, scenario.newServerSslContextFactory());
        connector.setPort(0);
        server.addConnector(connector);
        server.setHandler(handler);
        server.start();
    }

    protected void startClient(final Scenario scenario) throws Exception
    {
        startClient(scenario, null);
    }

    protected void startClient(final Scenario scenario, Consumer<HttpClient> config) throws Exception
    {
        ClientConnector clientConnector = new ClientConnector();
        clientConnector.setSelectors(1);
        clientConnector.setSslContextFactory(scenario.newClientSslContextFactory());
        HttpClientTransport transport = new HttpClientTransportOverHTTP(clientConnector);
        QueuedThreadPool executor = new QueuedThreadPool();
        executor.setName("client");
        clientConnector.setExecutor(executor);
        Scheduler scheduler = new ScheduledExecutorScheduler("client-scheduler", false);
        clientConnector.setScheduler(scheduler);
        client = newHttpClient(transport);
        client.setSocketAddressResolver(new SocketAddressResolver.Sync());
        if (config != null)
            config.accept(client);
        client.start();
    }

    public HttpClient newHttpClient(HttpClientTransport transport)
    {
        return new HttpClient(transport);
    }

    @AfterEach
    public void disposeClient() throws Exception
    {
        if (client != null)
        {
            client.stop();
            client = null;
        }
    }

    @AfterEach
    public void disposeServer() throws Exception
    {
        if (server != null)
        {
            server.stop();
            server = null;
        }
    }

    public static class ScenarioProvider implements ArgumentsProvider
    {
        @Override
        public Stream<? extends Arguments> provideArguments(ExtensionContext context)
        {
            return Stream.of(
                new NormalScenario(),
                new SslScenario()
                // TODO: add more ssl / non-ssl scenarios here
            ).map(Arguments::of);
        }
    }

    public static class NonSslScenarioProvider implements ArgumentsProvider
    {
        @Override
        public Stream<? extends Arguments> provideArguments(ExtensionContext context)
        {
            return Stream.of(
                new NormalScenario()
                // TODO: add more non-ssl scenarios here
            ).map(Arguments::of);
        }
    }

    public interface Scenario
    {
        SslContextFactory.Client newClientSslContextFactory();

        SslContextFactory.Server newServerSslContextFactory();

        String getScheme();
    }

    public static class NormalScenario implements Scenario
    {
        @Override
        public SslContextFactory.Client newClientSslContextFactory()
        {
            return null;
        }

        @Override
        public SslContextFactory.Server newServerSslContextFactory()
        {
            return null;
        }

        @Override
        public String getScheme()
        {
            return HttpScheme.HTTP.asString();
        }

        @Override
        public String toString()
        {
            return "HTTP";
        }
    }

    public static class SslScenario implements Scenario
    {
        @Override
        public SslContextFactory.Client newClientSslContextFactory()
        {
            SslContextFactory.Client result = new SslContextFactory.Client();
            result.setEndpointIdentificationAlgorithm(null);
            configure(result);
            return result;
        }

        @Override
        public SslContextFactory.Server newServerSslContextFactory()
        {
            SslContextFactory.Server result = new SslContextFactory.Server();
            configure(result);
            return result;
        }

        private void configure(SslContextFactory ssl)
        {
            Path keystorePath = MavenTestingUtils.getTestResourcePath("keystore.jks");
            ssl.setKeyStorePath(keystorePath.toString());
            ssl.setKeyStorePassword("storepwd");
        }

        @Override
        public String getScheme()
        {
            return HttpScheme.HTTPS.asString();
        }

        @Override
        public String toString()
        {
            return "HTTPS";
        }
    }
}
