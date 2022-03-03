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

package org.eclipse.jetty.ee9.test;

import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import jakarta.annotation.Resource;
import jakarta.servlet.AsyncContext;
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.ee9.annotations.AnnotationConfiguration;
import org.eclipse.jetty.ee9.plus.jndi.EnvEntry;
import org.eclipse.jetty.ee9.plus.webapp.EnvConfiguration;
import org.eclipse.jetty.ee9.plus.webapp.PlusConfiguration;
import org.eclipse.jetty.ee9.servlet.ServletHolder;
import org.eclipse.jetty.ee9.webapp.Configuration;
import org.eclipse.jetty.ee9.webapp.FragmentConfiguration;
import org.eclipse.jetty.ee9.webapp.MetaInfConfiguration;
import org.eclipse.jetty.ee9.webapp.WebAppConfiguration;
import org.eclipse.jetty.ee9.webapp.WebAppContext;
import org.eclipse.jetty.ee9.webapp.WebInfConfiguration;
import org.eclipse.jetty.ee9.webapp.WebXmlConfiguration;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class AnnotatedAsyncListenerTest
{
    private Server server;
    private ServerConnector connector;
    private HttpClient client;

    private void start(HttpServlet servlet) throws Exception
    {
        QueuedThreadPool serverExecutor = new QueuedThreadPool();
        serverExecutor.setName("server");
        server = new Server(serverExecutor);
        connector = new ServerConnector(server, 1, 1);
        server.addConnector(connector);

        Path webAppDir = MavenTestingUtils.getTargetTestingPath(AnnotatedAsyncListenerTest.class.getName() + "@" + servlet.hashCode());
        Path webInf = webAppDir.resolve("WEB-INF");
        Files.createDirectories(webInf);

        try (BufferedWriter writer = Files.newBufferedWriter(webInf.resolve("web.xml"), StandardCharsets.UTF_8, StandardOpenOption.CREATE))
        {
            writer.write("<web-app xmlns=\"http://xmlns.jcp.org/xml/ns/javaee\" " +
                "xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" " +
                "xsi:schemaLocation=\"http://xmlns.jcp.org/xml/ns/javaee http://xmlns.jcp.org/xml/ns/javaee/web-app_3_1.xsd\" " +
                "version=\"3.1\">" +
                "</web-app>");
        }

        WebAppContext context = new WebAppContext(webAppDir.toString(), "/");
        context.setConfigurations(new Configuration[]
            {
                new AnnotationConfiguration(),
                new WebXmlConfiguration(),
                new WebInfConfiguration(),
                new MetaInfConfiguration(),
                new FragmentConfiguration(),
                new EnvConfiguration(),
                new PlusConfiguration(),
                new WebAppConfiguration()
            });
        context.addServlet(new ServletHolder(servlet), "/*");
        new EnvEntry(context, "value", 1307D, false);
        server.setHandler(context);

        QueuedThreadPool clientExecutor = new QueuedThreadPool();
        clientExecutor.setName("client");
        client = new HttpClient();
        client.setExecutor(clientExecutor);
        server.addBean(client);

        server.start();
    }

    @AfterEach
    public void dispose() throws Exception
    {
        server.stop();
    }

    @Test
    public void testAnnotatedAsyncListener() throws Exception
    {
        start(new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse response) throws ServletException
            {
                AsyncContext asyncContext = request.startAsync();
                AnnotatedAsyncListener listener = asyncContext.createListener(AnnotatedAsyncListener.class);
                assertEquals(listener.value, 1307D);
                asyncContext.complete();
            }
        });

        ContentResponse response = client.GET("http://localhost:" + connector.getLocalPort() + "/test");
        assertEquals(HttpStatus.OK_200, response.getStatus());
    }

    public static class AnnotatedAsyncListener implements AsyncListener
    {
        @Resource(mappedName = "value")
        private Double value;

        @Override
        public void onComplete(AsyncEvent event)
        {
        }

        @Override
        public void onTimeout(AsyncEvent event)
        {
        }

        @Override
        public void onError(AsyncEvent event)
        {
        }

        @Override
        public void onStartAsync(AsyncEvent event)
        {
        }
    }
}
