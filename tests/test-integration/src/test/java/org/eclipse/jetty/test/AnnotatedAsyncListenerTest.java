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

package org.eclipse.jetty.test;

import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import javax.annotation.Resource;
import javax.servlet.AsyncContext;
import javax.servlet.AsyncEvent;
import javax.servlet.AsyncListener;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.annotations.AnnotationConfiguration;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.plus.jndi.EnvEntry;
import org.eclipse.jetty.plus.webapp.EnvConfiguration;
import org.eclipse.jetty.plus.webapp.PlusConfiguration;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.eclipse.jetty.webapp.Configuration;
import org.eclipse.jetty.webapp.FragmentConfiguration;
import org.eclipse.jetty.webapp.MetaInfConfiguration;
import org.eclipse.jetty.webapp.WebAppContext;
import org.eclipse.jetty.webapp.WebInfConfiguration;
import org.eclipse.jetty.webapp.WebXmlConfiguration;
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
                new PlusConfiguration()
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
