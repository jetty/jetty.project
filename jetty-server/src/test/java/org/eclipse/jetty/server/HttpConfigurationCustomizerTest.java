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

package org.eclipse.jetty.server;

import java.io.IOException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpScheme;
import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;

public class HttpConfigurationCustomizerTest
{
    private Server server;
    private LocalConnector localConnector;

    @BeforeEach
    public void startServer() throws Exception
    {
        server = new Server();

        HttpConnectionFactory http = new HttpConnectionFactory();
        http.getHttpConfiguration().setSecurePort(9999);
        http.getHttpConfiguration().setSecureScheme("https");

        HttpConnectionFactory https = new HttpConnectionFactory(http.getHttpConfiguration());
        https.getHttpConfiguration().addCustomizer((connector, channelConfig, request) -> {
            // INVALID: final PreEncodedHttpField X_XSS_PROTECTION_FIELD = new PreEncodedHttpField("X-XSS-Protection", "1; mode=block");
            final HttpField X_XSS_PROTECTION_FIELD = new HttpField("X-XSS-Protection", "1; mode=block");
            request.setScheme(HttpScheme.HTTPS.asString());
            request.setSecure(true);
            request.getResponse().getHttpFields().add(X_XSS_PROTECTION_FIELD); // test response header
        });

        localConnector = new LocalConnector(server, https);
        server.addConnector(localConnector);

        ContextHandler context = new ContextHandler();
        context.setContextPath("/ctx");

        context.setHandler(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                response.setStatus(200);
                response.getWriter().print("Success");
                baseRequest.setHandled(true);
            }
        });

        server.setHandler(context);
        server.start();
    }

    @AfterEach
    public void stopServer() throws Exception
    {
        server.stop();
    }

    @Test
    public void testCustomizer() throws Exception
    {
        String request = "GET /ctx/ HTTP/1.1\r\n" +
                "Host: local\r\n" +
                "Connection: close\r\n" +
                "\r\n";

        String rawResponse = localConnector.getResponse(request);
        System.out.println(rawResponse);
        HttpTester.Response response = HttpTester.parseResponse(rawResponse);
        assertThat("Response status", response.getStatus(), is(200));
        assertThat("Response body", response.getContent(), containsString("Success"));

        String value = response.get("X-XSS-Protection");
        assertThat("X-XSS-Protection value", value, is("1; mode=block"));
    }
}
