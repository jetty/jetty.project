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

package org.eclipse.jetty.websocket.client;

import java.net.URL;
import java.net.URLClassLoader;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Executor;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.eclipse.jetty.util.thread.ThreadClassLoaderScope;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.hamcrest.core.Is.is;

public class HttpClientInitTest
{
    @Test
    public void testDefaultInit() throws Exception
    {
        WebSocketClient client = new WebSocketClient();
        try
        {
            client.start();
            HttpClient httpClient = client.getHttpClient();
            assertThat("HttpClient exists", httpClient, notNullValue());
            assertThat("HttpClient is started", httpClient.isStarted(), is(true));
            Executor executor = httpClient.getExecutor();
            assertThat("Executor exists", executor, notNullValue());
            assertThat("Executor instanceof", executor, instanceOf(QueuedThreadPool.class));
            QueuedThreadPool threadPool = (QueuedThreadPool)executor;
            assertThat("QueuedThreadPool.name", threadPool.getName(), startsWith("WebSocketClient@"));
        }
        finally
        {
            client.stop();
        }
    }

    @Test
    public void testManualInit() throws Exception
    {
        HttpClient http = new HttpClient();
        {
            QueuedThreadPool threadPool = new QueuedThreadPool();
            threadPool.setName("ManualWSClient@" + http.hashCode());
            http.setExecutor(threadPool);
            http.setConnectTimeout(7777);
        }

        WebSocketClient client = new WebSocketClient(http);
        client.addBean(http);
        try
        {
            client.start();
            HttpClient httpClient = client.getHttpClient();
            assertThat("HttpClient exists", httpClient, notNullValue());
            assertThat("HttpClient is started", httpClient.isStarted(), is(true));
            assertThat("HttpClient.connectTimeout", httpClient.getConnectTimeout(), is(7777L));
            Executor executor = httpClient.getExecutor();
            assertThat("Executor exists", executor, notNullValue());
            assertThat("Executor instanceof", executor, instanceOf(QueuedThreadPool.class));
            QueuedThreadPool threadPool = (QueuedThreadPool)executor;
            assertThat("QueuedThreadPool.name", threadPool.getName(), startsWith("ManualWSClient@"));
        }
        finally
        {
            client.stop();
        }
    }

    @Test
    public void testXmlResourceInit() throws Exception
    {
        ClassLoader parent = Thread.currentThread().getContextClassLoader();
        URL[] urls = new URL[]{
            MavenTestingUtils.getTestResourceDir("httpclient/simple").toURI().toURL()
        };
        URLClassLoader classLoader = new URLClassLoader(urls, parent);

        try (ThreadClassLoaderScope scope = new ThreadClassLoaderScope(classLoader))
        {
            WebSocketClient client = new WebSocketClient();
            try
            {
                client.start();
                HttpClient httpClient = client.getHttpClient();
                assertThat("HttpClient exists", httpClient, notNullValue());
                assertThat("HttpClient is started", httpClient.isStarted(), is(true));
                assertThat("HttpClient.connectTimeout", httpClient.getConnectTimeout(), is(5555L));

                SslContextFactory sslContextFactory = httpClient.getSslContextFactory();
                List<String> actualExcludedProtocols = Arrays.asList(sslContextFactory.getExcludeProtocols());
                assertThat("HttpClient.sslContextFactory.excludedProtocols", actualExcludedProtocols, hasItem("TLS/0.1"));

                Executor executor = httpClient.getExecutor();
                assertThat("Executor exists", executor, notNullValue());
                assertThat("Executor instanceof", executor, instanceOf(QueuedThreadPool.class));
                QueuedThreadPool threadPool = (QueuedThreadPool)executor;
                assertThat("QueuedThreadPool.name", threadPool.getName(), startsWith("XmlBasedClient@"));
            }
            finally
            {
                client.stop();
            }
        }
    }
}
