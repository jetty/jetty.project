//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.ee.servlet;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;
import java.util.function.Consumer;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.http.HttpCompliance;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.component.LifeCycle;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

public class RequestHeadersTest
{
    private Server server;
    private URI serverUri;

    public void startServer(Consumer<ServletContextHandler> servletContextConsumer) throws Exception
    {
        // Configure Server
        server = new Server();
        ServerConnector connector = new ServerConnector(server);
        server.addConnector(connector);

        ServletContextHandler context = new ServletContextHandler();
        context.setContextPath("/");
        server.setHandler(context);

        servletContextConsumer.accept(context);

        // Start Server
        server.start();
        serverUri = server.getURI().resolve("/");
    }

    @AfterEach
    public void stopServer()
    {
        LifeCycle.stop(server);
    }

    @Test
    public void testGetLowercaseHeader() throws Exception
    {
        startServer((context) ->
        {
            HttpServlet requestHeaderServlet = new HttpServlet()
            {
                @Override
                protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException
                {
                    resp.setContentType("text/plain");
                    PrintWriter out = resp.getWriter();
                    out.printf("X-Camel-Type = %s", req.getHeader("X-Camel-Type"));
                }
            };

            // Serve capture servlet
            context.addServlet(new ServletHolder(requestHeaderServlet), "/*");
        });

        HttpURLConnection http = null;
        try
        {
            http = (HttpURLConnection)serverUri.toURL().openConnection();
            // Set header in all lowercase
            http.setRequestProperty("x-camel-type", "bactrian");

            try (InputStream in = http.getInputStream())
            {
                String resp = IO.toString(in, StandardCharsets.UTF_8);
                assertThat("Response", resp, is("X-Camel-Type = bactrian"));
            }
        }
        finally
        {
            if (http != null)
            {
                http.disconnect();
            }
        }
    }

    @ParameterizedTest
    @CsvSource(
        delimiter = '|',
        textBlock = """
            # Request Value | Expected Locale
            ;q=0.5          | en-US
            q=0.6           | en-US
            de              | de
            en-GB           | en-GB
            en;q=0.5,it     | it
            bogus           | bogus
            bogus,en-US     | en-US
            en_en           | en-US
            en-FO           | en-FO
            en-cockney      | en-cockney
            de-DE-1996      | de-DE-1996
            th-TH-u-nu-thai-x-lvariant-TH | th-TH-u-nu-thai-x-lvariant-TH
            x-pig-latin     | en-US
            ;-              | en-US
            ";--            | en-US
            """)
    public void testLocale(String requestHeaderValue, String expectedLocale) throws Exception
    {
        startServer((context) ->
        {
            Arrays.asList(context.getServer().getConnectors()).forEach(c -> c.getConnectionFactory(HttpConnectionFactory.class).getHttpConfiguration().setHttpCompliance(HttpCompliance.LEGACY));
            HttpServlet requestHeaderServlet = new HttpServlet()
            {
                @Override
                protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException
                {
                    Locale locale = req.getLocale();
                    resp.setContentType("text/plain");
                    PrintWriter out = resp.getWriter();
                    out.print("Locale = ");
                    String langTag = locale.toLanguageTag();
                    out.println(langTag);
                }
            };

            // Serve capture servlet
            context.addServlet(new ServletHolder(requestHeaderServlet), "/*");
        });

        HttpURLConnection http = null;
        try
        {
            http = (HttpURLConnection)serverUri.toURL().openConnection();
            // Set header in all lowercase
            http.setRequestProperty("Accept-Language", requestHeaderValue);

            try (InputStream in = http.getInputStream())
            {
                String resp = IO.toString(in, StandardCharsets.UTF_8);
                assertThat("Response", resp, is("Locale = " + expectedLocale + "\n"));
            }
        }
        finally
        {
            if (http != null)
            {
                http.disconnect();
            }
        }
    }
}
