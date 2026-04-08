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

package org.eclipse.jetty.ee11.test.client.transport;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.client.ContentResponse;
import org.eclipse.jetty.ee11.servlet.ServletContextRequest;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

public class NeedClientAuthTest extends AbstractTest
{
    @ParameterizedTest
    @MethodSource("transportsSecure")
    public void testNeedClientAuth(TransportType transportType) throws Exception
    {
        prepareServer(transportType, new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse response)
            {
                // Verify that the request attribute is present.
                assertNotNull(request.getAttribute(ServletContextRequest.PEER_CERTIFICATES));
            }
        });
        sslContextFactoryServer.setNeedClientAuth(true);
        server.start();

        startClient(transportType, httpClient ->
        {
            // Configure the SslContextFactory to send a certificate to the server.
            SslContextFactory.Client clientSSL = httpClient.getSslContextFactory();
            clientSSL.setKeyStorePath("src/test/resources/keystore.p12");
            clientSSL.setKeyStorePassword("storepwd");
            clientSSL.setCertAlias("mykey");
        });

        ContentResponse response = client.newRequest(newURI(transportType)).send();

        assertEquals(HttpStatus.OK_200, response.getStatus());
    }
}
