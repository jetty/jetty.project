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

package org.eclipse.jetty.server.ssl;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import javax.net.ssl.SSLContext;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.junit.jupiter.api.Test;

public class SSLCloseTest
{
    @Test
    public void testClose() throws Exception
    {
        File keystore = MavenTestingUtils.getTestResourceFile("keystore.p12");
        SslContextFactory.Server sslContextFactory = new SslContextFactory.Server();
        sslContextFactory.setKeyStoreResource(Resource.newResource(keystore));
        sslContextFactory.setKeyStorePassword("storepwd");

        Server server = new Server();
        ServerConnector connector = new ServerConnector(server, sslContextFactory);
        connector.setPort(0);

        server.addConnector(connector);
        server.setHandler(new WriteHandler());
        server.start();

        SSLContext ctx = SSLContext.getInstance("TLSv1.2");
        ctx.init(null, SslContextFactory.TRUST_ALL_CERTS, new java.security.SecureRandom());

        int port = connector.getLocalPort();

        Socket socket = ctx.getSocketFactory().createSocket("localhost", port);
        OutputStream os = socket.getOutputStream();

        os.write((
            "GET /test HTTP/1.1\r\n" +
                "Host:test\r\n" +
                "Connection:close\r\n\r\n").getBytes());
        os.flush();

        BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));

        String line;
        while ((line = in.readLine()) != null)
        {
            if (line.trim().length() == 0)
                break;
        }

        Thread.sleep(2000);

        while (in.readLine() != null)
        {
            Thread.yield();
        }
    }

    private static class WriteHandler extends AbstractHandler
    {
        public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
        {
            try
            {
                baseRequest.setHandled(true);
                response.setStatus(200);
                response.setHeader("test", "value");

                OutputStream out = response.getOutputStream();

                String data = "Now is the time for all good men to come to the aid of the party.\n";
                data += "How now brown cow.\n";
                data += "The quick brown fox jumped over the lazy dog.\n";
                // data=data+data+data+data+data+data+data+data+data+data+data+data+data;
                // data=data+data+data+data+data+data+data+data+data+data+data+data+data;
                data = data + data + data + data;
                byte[] bytes = data.getBytes(StandardCharsets.UTF_8);

                for (int i = 0; i < 2; i++)
                {
                    // System.err.println("Write "+i+" "+bytes.length);
                    out.write(bytes);
                }
            }
            catch (Throwable e)
            {
                e.printStackTrace();
                throw new ServletException(e);
            }
        }
    }
}
