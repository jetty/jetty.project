//
// ========================================================================
// Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.http3.client;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.http.HttpClientTransportOverHTTP;
import org.eclipse.jetty.http.HttpCompliance;
import org.eclipse.jetty.http3.server.ServerQuicConnector;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;

public class End2EndClientTest
{
    private Server server;
    private HttpClient client;

    @BeforeEach
    public void setUp() throws Exception
    {
        server = new Server();

        HttpConfiguration config = new HttpConfiguration();
        config.setHttpCompliance(HttpCompliance.LEGACY); // enable HTTP/0.9
        HttpConnectionFactory connectionFactory = new HttpConnectionFactory(config);

        ServerQuicConnector connector = new ServerQuicConnector(server, connectionFactory);
        connector.setPort(8443);
        server.addConnector(connector);

        server.setHandler(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                baseRequest.setHandled(true);
                PrintWriter writer = response.getWriter();
                writer.println("<html>\n" +
                    "\t<body>\n" +
                    "\t\tRequest served\n" +
                    "\t</body>\n" +
                    "</html>");
            }
        });

        server.start();

        HttpClientTransportOverHTTP transport = new HttpClientTransportOverHTTP(new ClientQuicConnector());
        client = new HttpClient(transport);
        client.start();
    }

    @AfterEach
    public void tearDown() throws Exception
    {
        try
        {
            server.stop();
        }
        finally
        {
            client.stop();
        }
    }

    @Test
    public void simple() throws Exception
    {
        ContentResponse response = client.GET("https://localhost:8443/");
        int status = response.getStatus();
        String contentAsString = response.getContentAsString();
        System.out.println("==========");
        System.out.println("Status: " + status);
        System.out.println(contentAsString);
        System.out.println("==========");

        assertThat(status, is(200));
        assertThat(contentAsString, is("<html>\n" +
            "\t<body>\n" +
            "\t\tRequest served\n" +
            "\t</body>\n" +
            "</html>\n"));
    }

    @Test
    public void multiple() throws Exception
    {
        for (int i = 0; i < 1000; i++)
        {
            ContentResponse response = client.GET("https://localhost:8443/");
            int status = response.getStatus();
            String contentAsString = response.getContentAsString();
            assertThat(status, is(200));
            assertThat(contentAsString, is("<html>\n" +
                "\t<body>\n" +
                "\t\tRequest served\n" +
                "\t</body>\n" +
                "</html>\n"));
        }
    }

    @Test
    public void multiThreaded() throws Exception
    {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try
        {
            List<Future<?>> futures = new ArrayList<>();

            for (int i = 0; i < 1000; i++)
            {
                futures.add(executor.submit(() ->
                {
                    try
                    {
                        ContentResponse response = client.GET("https://localhost:8443/");
                        int status = response.getStatus();
                        String contentAsString = response.getContentAsString();
                        assertThat(status, is(200));
                        assertThat(contentAsString, is("<html>\n" +
                            "\t<body>\n" +
                            "\t\tRequest served\n" +
                            "\t</body>\n" +
                            "</html>\n"));
                    }
                    catch (Exception e)
                    {
                        throw new RuntimeException(e);
                    }
                }));
            }

            for (Future<?> future : futures)
            {
                future.get();
            }
        }
        finally
        {
            executor.shutdownNow();
        }
    }
}
