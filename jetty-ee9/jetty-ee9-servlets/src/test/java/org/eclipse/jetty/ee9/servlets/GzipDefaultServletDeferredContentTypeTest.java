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

package org.eclipse.jetty.ee9.servlets;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.ee9.servlet.DefaultServlet;
import org.eclipse.jetty.ee9.servlet.ServletContextHandler;
import org.eclipse.jetty.ee9.servlet.ServletHolder;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.gzip.GzipHandler;
import org.eclipse.jetty.toolchain.test.FS;
import org.eclipse.jetty.toolchain.test.Sha1Sum;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.util.resource.Resource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.emptyOrNullString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

/**
 * GzipHandler setting of headers when reset and/or not compressed.
 *
 * The GzipHandler now sets deferred headers (content-length and etag) when it decides not to commit.
 * Also does not allow a reset after a decision to commit
 *
 * Originally from http://bugs.eclipse.org/408909
 */
public class GzipDefaultServletDeferredContentTypeTest extends AbstractGzipTest
{
    private Server server;

    @AfterEach
    public void stopServer()
    {
        LifeCycle.stop(server);
    }

    @Test
    public void testIsNotGzipCompressedByDeferredContentType() throws Exception
    {
        server = new Server();
        LocalConnector localConnector = new LocalConnector(server);
        server.addConnector(localConnector);

        Path contextDir = workDir.resolve("context");
        FS.ensureDirExists(contextDir);

        ServletContextHandler servletContextHandler = new ServletContextHandler();
        servletContextHandler.setContextPath("/context");
        servletContextHandler.setBaseResource(Resource.newResource(contextDir));
        ServletHolder holder = new ServletHolder("default", new DefaultServlet()
        {
            @Override
            public void service(HttpServletRequest req, HttpServletResponse resp) throws IOException, ServletException
            {
                String uri = req.getRequestURI();
                if (uri.endsWith(".deferred"))
                {
                    // System.err.println("type for "+uri.substring(0,uri.length()-9)+" is "+getServletContext().getMimeType(uri.substring(0,uri.length()-9)));
                    resp.setContentType(getServletContext().getMimeType(uri.substring(0, uri.length() - 9)));
                }

                doGet(req, resp);
            }
        });
        servletContextHandler.addServlet(holder, "/");

        GzipHandler gzipHandler = new GzipHandler();
        gzipHandler.setHandler(servletContextHandler);
        server.setHandler(gzipHandler);

        int fileSize = DEFAULT_OUTPUT_BUFFER_SIZE * 4;

        Path file = createFile(contextDir, "file.mp3.deferred", fileSize);
        String expectedSha1Sum = Sha1Sum.calculate(file);

        server.start();

        // Setup request
        HttpTester.Request request = HttpTester.newRequest();
        request.setMethod("GET");
        request.setVersion(HttpVersion.HTTP_1_1);
        request.setHeader("Host", "tester");
        request.setHeader("Connection", "close");
        request.setHeader("Accept-Encoding", "gzip");
        request.setURI("/context/file.mp3.deferred");

        // Issue request
        ByteBuffer rawResponse = localConnector.getResponse(request.generate(), 5, TimeUnit.SECONDS);

        // Parse response
        HttpTester.Response response = HttpTester.parseResponse(rawResponse);

        assertThat("Response status", response.getStatus(), is(HttpStatus.OK_200));

        // Response Content-Encoding check
        assertThat("Response[Content-Encoding]", response.get("Content-Encoding"), not(containsString("gzip")));

        // Response Vary check
        assertThat("Response[Vary]", response.get("Vary"), is(emptyOrNullString()));

        // Response Content checks
        UncompressedMetadata metadata = parseResponseContent(response);
        assertThat("(Uncompressed) Content Length", metadata.uncompressedSize, is(fileSize));
        assertThat("(Uncompressed) Content Hash", metadata.uncompressedSha1Sum, is(expectedSha1Sum));
    }
}
