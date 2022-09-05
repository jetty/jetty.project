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

package org.eclipse.jetty.servlets;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;

import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDir;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDirExtension;
import org.eclipse.jetty.util.NanoTime;
import org.eclipse.jetty.util.resource.Resource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThan;

@ExtendWith(WorkDirExtension.class)
public class DataRateLimitedServletTest
{
    public static final int BUFFER = 8192;
    public static final int PAUSE = 10;

    public WorkDir testdir;

    private Server server;
    private LocalConnector connector;
    private ServletContextHandler context;

    @BeforeEach
    public void init() throws Exception
    {
        server = new Server();

        connector = new LocalConnector(server);
        connector.getConnectionFactory(HttpConfiguration.ConnectionFactory.class).getHttpConfiguration().setSendServerVersion(false);

        context = new ServletContextHandler();

        context.setContextPath("/context");
        context.setWelcomeFiles(new String[]{"index.html", "index.jsp", "index.htm"});

        File baseResourceDir = testdir.getEmptyPathDir().toFile();
        // Use resolved real path for Windows and OSX
        Path baseResourcePath = baseResourceDir.toPath().toRealPath();

        context.setBaseResource(Resource.newResource(baseResourcePath.toFile()));

        ServletHolder holder = context.addServlet(DataRateLimitedServlet.class, "/stream/*");
        holder.setInitParameter("buffersize", "" + BUFFER);
        holder.setInitParameter("pause", "" + PAUSE);
        server.setHandler(context);
        server.addConnector(connector);

        server.start();
    }

    @AfterEach
    public void destroy() throws Exception
    {
        server.stop();
        server.join();
    }

    @Test
    public void testStream() throws Exception
    {
        File content = testdir.getPathFile("content.txt").toFile();
        String[] results = new String[10];
        try (OutputStream out = new FileOutputStream(content);)
        {
            byte[] b = new byte[1024];

            for (int i = 1024; i-- > 0; )
            {
                int index = i % 10;
                Arrays.fill(b, (byte)('0' + (index)));
                out.write(b);
                out.write('\n');
                if (results[index] == null)
                    results[index] = new String(b, StandardCharsets.US_ASCII);
            }
        }

        long start = NanoTime.now();
        String response = connector.getResponse("GET /context/stream/content.txt HTTP/1.0\r\n\r\n");
        long duration = NanoTime.millisSince(start);

        assertThat("Response", response, containsString("200 OK"));
        assertThat("Response Length", response.length(), greaterThan(1024 * 1024));
        assertThat("Duration", duration, greaterThan(PAUSE * 1024L * 1024 / BUFFER));

        for (int i = 0; i < 10; i++)
        {
            assertThat(response, containsString(results[i]));
        }
    }
}
