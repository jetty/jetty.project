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

package org.eclipse.jetty.ee9.servlet;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.nio.ByteBuffer;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.Server;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

public class CharacterEncodingTest
{
    public static class CharsetChangeToJsonMimeTypeSetCharsetToNullServlet extends HttpServlet
    {
        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
        {
            // set an unknown character encoding
            response.setCharacterEncoding("allez-les-bleus");

            // here we should have UnsupportedEncodingException
            try
            {
                response.getWriter();
            }
            catch (UnsupportedEncodingException e)
            {
                // nothing we only test we throw this exception
            }
        }
    }

    private static Server server;
    private static LocalConnector connector;

    @BeforeAll
    public static void startServer() throws Exception
    {
        server = new Server();
        connector = new LocalConnector(server);
        server.addConnector(connector);

        ServletContextHandler context = new ServletContextHandler();
        context.setContextPath("/");
        server.setHandler(context);

        context.addServlet(CharsetChangeToJsonMimeTypeSetCharsetToNullServlet.class, "/character-encoding/not-exists/*");

        server.start();
    }

    @AfterAll
    public static void stopServer()
    {
        try
        {
            server.stop();
        }
        catch (Exception e)
        {
            e.printStackTrace(System.err);
        }
    }

    @Test
    public void testUnknownCharacterEncoding() throws Exception
    {
        HttpTester.Request request = new HttpTester.Request();
        request.setMethod("GET");
        request.setURI("/character-encoding/not-exists/");
        request.setVersion(HttpVersion.HTTP_1_1);
        request.setHeader("Host", "test");

        ByteBuffer responseBuffer = connector.getResponse(request.generate());
        HttpTester.Response response = HttpTester.parseResponse(responseBuffer);

        // Now test for properly formatted HTTP Response Headers.
        assertThat("Response Code", response.getStatus(), is(200));

    }
}
