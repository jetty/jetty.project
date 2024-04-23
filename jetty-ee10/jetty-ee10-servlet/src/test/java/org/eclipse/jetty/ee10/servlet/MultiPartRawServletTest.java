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

package org.eclipse.jetty.ee10.servlet;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import jakarta.servlet.MultipartConfigElement;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.Part;
import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.ContextHandlerCollection;
import org.eclipse.jetty.tests.multipart.MultiPartExpectations;
import org.eclipse.jetty.tests.multipart.MultiPartFormArgumentsProvider;
import org.eclipse.jetty.tests.multipart.MultiPartRequest;
import org.eclipse.jetty.tests.multipart.MultiPartResults;
import org.eclipse.jetty.toolchain.test.FS;
import org.eclipse.jetty.toolchain.test.MavenPaths;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.component.LifeCycle;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ArgumentsSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

/**
 * Test various raw Multipart Requests against the ee10 servlet implementation
 */
public class MultiPartRawServletTest
{
    private Server server;
    private URI serverURI;

    private void startServer(Consumer<ServletContextHandler> configureContext) throws Exception
    {
        Path tempDir = MavenPaths.targetTestDir(MultiPartRawServletTest.class.getSimpleName());
        FS.ensureDirExists(tempDir);
        server = new Server();

        ServerConnector connector = new ServerConnector(server);
        connector.setPort(0);
        server.addConnector(connector);

        ContextHandlerCollection contexts = new ContextHandlerCollection();
        server.setHandler(contexts);

        ServletContextHandler servletContextHandler = new ServletContextHandler();
        servletContextHandler.setContextPath("/app");
        servletContextHandler.setTempDirectory(tempDir.toFile());

        configureContext.accept(servletContextHandler);
        contexts.addHandler(servletContextHandler);

        server.start();
        serverURI = server.getURI().resolve("/");
    }

    @AfterEach
    public void stopServer()
    {
        LifeCycle.stop(server);
    }

    @ParameterizedTest
    @ArgumentsSource(MultiPartFormArgumentsProvider.class)
    public void testMultiPartFormDataParse(MultiPartRequest formRequest, Charset defaultCharset, MultiPartExpectations formExpectations) throws Exception
    {
        startServer((servletContextHandler) ->
        {
            MultiPartValidationServlet servlet = new MultiPartValidationServlet(formExpectations, defaultCharset);
            ServletHolder servletHolder = new ServletHolder(servlet);
            MultipartConfigElement config = new MultipartConfigElement(null, 1_500_000, 2_000_000, 3_000_000);
            servletHolder.getRegistration().setMultipartConfig(config);
            servletContextHandler.addServlet(servletHolder, "/multipart/*");
        });

        try (Socket client = new Socket(serverURI.getHost(), serverURI.getPort());
             OutputStream output = client.getOutputStream();
             InputStream input = client.getInputStream())
        {
            ByteBuffer bodyBuffer = formRequest.asByteBuffer();

            StringBuilder reqBuilder = new StringBuilder();
            reqBuilder.append("POST /app/multipart/");
            reqBuilder.append(formRequest.getFormName());
            reqBuilder.append(" HTTP/1.1\r\n");
            reqBuilder.append("Host: ").append(serverURI.getAuthority()).append("\r\n");
            AtomicBoolean hasContentTypeHeader = new AtomicBoolean(false);
            List<String> droppedHeaders = List.of("host", "content-length", "transfer-encoding");
            formRequest.getHeaders().forEach((name, value) ->
            {
                String namelower = name.toLowerCase(Locale.ENGLISH);
                if (!droppedHeaders.contains(namelower))
                {
                    if (namelower.equals("content-type"))
                        hasContentTypeHeader.set(true);
                    reqBuilder.append(name).append(": ").append(value).append("\r\n");
                }
            });
            if (!hasContentTypeHeader.get())
                reqBuilder.append("Content-Type: ").append(formExpectations.getContentType()).append("\r\n");
            reqBuilder.append("Content-Length: ").append(bodyBuffer.remaining()).append("\r\n");
            reqBuilder.append("\r\n");

            output.write(reqBuilder.toString().getBytes(StandardCharsets.UTF_8));
            output.write(BufferUtil.toArray(bodyBuffer));
            output.flush();

            HttpTester.Response response = HttpTester.parseResponse(input);
            assertThat(response.getStatus(), is(200));
        }
    }

    public static class MultiPartValidationServlet extends HttpServlet
    {
        private final MultiPartExpectations multiPartExpectations;
        private final Charset defaultCharset;

        public MultiPartValidationServlet(MultiPartExpectations expectations, Charset defaultCharset)
        {
            this.multiPartExpectations = expectations;
            this.defaultCharset = defaultCharset;
        }

        @Override
        protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException
        {
            try
            {
                multiPartExpectations.assertParts(mapActualResults(req.getParts()), defaultCharset);
            }
            catch (Exception e)
            {
                throw new ServletException("Failed to validate multipart/form-data", e);
            }
        }

        private MultiPartResults mapActualResults(final Collection<Part> parts)
        {
            return new MultiPartResults()
            {
                @Override
                public int getCount()
                {
                    return parts.size();
                }

                @Override
                public List<PartResult> get(String name)
                {
                    List<PartResult> namedParts = new ArrayList<>();
                    for (Part part: parts)
                    {
                        if (part.getName().equalsIgnoreCase(name))
                        {
                            namedParts.add(new NamedPartResult(part));
                        }
                    }

                    return namedParts;
                }
            };
        }

        private class NamedPartResult implements MultiPartResults.PartResult
        {
            private final Part namedPart;

            public NamedPartResult(Part part)
            {
                this.namedPart = part;
            }

            @Override
            public String getContentType()
            {
                return namedPart.getContentType();
            }

            @Override
            public ByteBuffer asByteBuffer() throws IOException
            {
                try (InputStream inputStream = namedPart.getInputStream();
                     ByteArrayOutputStream baos = new ByteArrayOutputStream())
                {
                    IO.copy(inputStream, baos);
                    return ByteBuffer.wrap(baos.toByteArray());
                }
            }

            @Override
            public String asString(Charset charset) throws IOException
            {
                return IO.toString(namedPart.getInputStream(), charset);
            }

            @Override
            public String getFileName()
            {
                return namedPart.getSubmittedFileName();
            }

            @Override
            public InputStream asInputStream() throws IOException
            {
                return namedPart.getInputStream();
            }
        }
    }
}
