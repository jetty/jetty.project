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

package org.eclipse.jetty.ee9.servlet;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.client.ContentResponse;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.compression.server.CompressionHandler;
import org.eclipse.jetty.compression.zstandard.ZstandardCompression;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.server.handler.ContextHandlerCollection;
import org.eclipse.jetty.server.handler.ResourceHandler;
import org.eclipse.jetty.toolchain.test.FS;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDir;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDirExtension;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.util.resource.ResourceFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

@ExtendWith(WorkDirExtension.class)
public class DefaultServletCompressionCompleteTest
{
    public WorkDir workDir;
    private Server server;

    @AfterEach
    public void stopServer()
    {
        LifeCycle.stop(server);
    }

    @Test
    public void testCompleteAfterDirectResourceWriteDoesNotFail() throws Exception
    {
        Path contextDir = workDir.getEmptyPathDir().resolve("context");
        FS.ensureDirExists(contextDir);

        Path file = contextDir.resolve("big.txt");
        String generatedContent = generateContent(64 * 1024);
        Files.writeString(file, generatedContent, StandardCharsets.UTF_8);

        ServletContextHandler context = new ServletContextHandler();
        context.setContextPath("/context");
        context.setBaseResourceAsPath(contextDir);
        context.addServlet(DefaultServlet.class, "/");

        CompressionHandler compressionHandler = new CompressionHandler();
        compressionHandler.setHandler(context);
        compressionHandler.putCompression(new ZstandardCompression());

        AtomicReference<Throwable> handlerFailure = new AtomicReference<>();
        Handler.Wrapper failureCapture = new Handler.Wrapper(compressionHandler)
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback) throws Exception
            {
                Callback wrapped = new Callback()
                {
                    @Override
                    public void succeeded()
                    {
                        callback.succeeded();
                    }

                    @Override
                    public void failed(Throwable x)
                    {
                        x.printStackTrace();
                        handlerFailure.set(x);
                        callback.failed(x);
                    }
                };
                return super.handle(request, response, wrapped);
            }
        };

        server = new Server();
        ServerConnector connector = new ServerConnector(server, 1, 1);
        server.addConnector(connector);
        server.setHandler(failureCapture);
        server.start();

        try (HttpClient httpClient = new HttpClient())
        {
            httpClient.start();

            // HttpClient transparently decodes zstd content and strips the Content-Encoding
            // header from the final response, so capture it as it arrives on the wire instead.
            AtomicReference<String> contentEncoding = new AtomicReference<>();
            ContentResponse response = httpClient.newRequest("localhost", connector.getLocalPort())
                .path("/context/big.txt")
                .headers(h -> h.put(HttpHeader.ACCEPT_ENCODING, "zstd"))
                .onResponseHeader((r, f) ->
                {
                    if (f.getHeader() == HttpHeader.CONTENT_ENCODING)
                        contentEncoding.set(f.getValue());
                    return true;
                })
                .timeout(15, TimeUnit.SECONDS)
                .send();

            assertThat(response.getStatus(), is(HttpStatus.OK_200));
            assertThat(contentEncoding.get(), is("zstd"));
            assertThat(response.getContentAsString(), is(generatedContent));
            assertThat(handlerFailure.get(), nullValue());
        }
    }

    @Test
    public void testCompleteAfterCrossContextForwardBypassWriteDoesNotFail() throws Exception
    {
        Path resourceDir = workDir.getEmptyPathDir().resolve("resource");
        FS.ensureDirExists(resourceDir);

        Path file = resourceDir.resolve("big.txt");
        String generatedContent = generateContent(64 * 1024);
        Files.writeString(file, generatedContent, StandardCharsets.UTF_8);

        ContextHandlerCollection contexts = new ContextHandlerCollection();

        ServletContextHandler context = new ServletContextHandler();
        context.setContextPath("/context");
        context.setCrossContextDispatchSupported(true);
        context.addServlet(CrossContextForwardServlet.class, "/*");
        contexts.addHandler(context);

        ResourceHandler resourceHandler = new ResourceHandler();
        resourceHandler.setBaseResource(ResourceFactory.root().newResource(resourceDir));
        ContextHandler resourceContext = new ContextHandler("/resource");
        resourceContext.setHandler(resourceHandler);
        resourceContext.setCrossContextDispatchSupported(true);
        contexts.addHandler(resourceContext);

        // Capture the failures of the writes done below the CompressionHandler, as a
        // failed write after the last one is not reported to the handler callback.
        List<Throwable> writeFailures = new CopyOnWriteArrayList<>();
        Handler.Wrapper writeFailureCapture = new Handler.Wrapper(contexts)
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback) throws Exception
            {
                Response wrappedResponse = new Response.Wrapper(request, response)
                {
                    @Override
                    public void write(boolean last, ByteBuffer byteBuffer, Callback callback)
                    {
                        super.write(last, byteBuffer, Callback.from(callback::succeeded, x ->
                        {
                            writeFailures.add(x);
                            callback.failed(x);
                        }));
                    }
                };
                return super.handle(request, wrappedResponse, callback);
            }
        };

        CompressionHandler compressionHandler = new CompressionHandler(writeFailureCapture);
        compressionHandler.putCompression(new ZstandardCompression());

        server = new Server();
        ServerConnector connector = new ServerConnector(server, 1, 1);
        server.addConnector(connector);
        server.setHandler(compressionHandler);
        server.start();

        try (HttpClient httpClient = new HttpClient())
        {
            httpClient.start();

            // HttpClient transparently decodes zstd content and strips the Content-Encoding
            // header from the final response, so capture it as it arrives on the wire instead.
            AtomicReference<String> contentEncoding = new AtomicReference<>();
            ContentResponse response = httpClient.newRequest("localhost", connector.getLocalPort())
                .path("/context/forward")
                .headers(h -> h.put(HttpHeader.ACCEPT_ENCODING, "zstd"))
                .onResponseHeader((r, f) ->
                {
                    if (f.getHeader() == HttpHeader.CONTENT_ENCODING)
                        contentEncoding.set(f.getValue());
                    return true;
                })
                .timeout(15, TimeUnit.SECONDS)
                .send();

            assertThat(response.getStatus(), is(HttpStatus.OK_200));
            assertThat(contentEncoding.get(), is("zstd"));
            assertThat(response.getContentAsString(), is(generatedContent));
            assertThat(writeFailures, empty());
        }
    }

    public static class CrossContextForwardServlet extends HttpServlet
    {
        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
        {
            request.getServletContext().getContext("/resource").getRequestDispatcher("/big.txt").forward(request, response);
        }
    }

    private String generateContent(int length)
    {
        String sample = """
                Lorem ipsum dolor sit amet, consectetur adipiscing elit. In quis felis nunc.
                Quisque suscipit mauris et ante auctor ornare rhoncus lacus aliquet. Pellentesque
                habitant morbi tristique senectus et netus et malesuada fames ac turpis egestas.
                """;
        StringBuilder result = new StringBuilder();
        while (result.length() < length)
        {
            result.append(sample);
        }
        result.setLength(length);
        return result.toString();
    }
}
