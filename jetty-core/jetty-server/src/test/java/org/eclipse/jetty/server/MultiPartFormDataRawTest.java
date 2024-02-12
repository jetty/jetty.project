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

package org.eclipse.jetty.server;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.http.MultiPart;
import org.eclipse.jetty.http.MultiPartFormData;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.tests.multipart.MultiPartExpectations;
import org.eclipse.jetty.tests.multipart.MultiPartFormArgumentsProvider;
import org.eclipse.jetty.tests.multipart.MultiPartRequest;
import org.eclipse.jetty.tests.multipart.MultiPartResults;
import org.eclipse.jetty.toolchain.test.FS;
import org.eclipse.jetty.toolchain.test.MavenPaths;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.component.LifeCycle;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ArgumentsSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

/**
 * Test various raw Multipart Requests against the core server implementation
 */
public class MultiPartFormDataRawTest
{
    private Server server;
    private URI serverURI;

    private void startServer(Handler handler) throws Exception
    {
        Path tempDir = MavenPaths.targetTestDir(MultiPartFormDataRawTest.class.getSimpleName());
        FS.ensureDirExists(tempDir);
        server = new Server();
        server.setTempDirectory(tempDir.toFile());

        ServerConnector connector = new ServerConnector(server);
        connector.setPort(0);
        server.addConnector(connector);

        server.setHandler(handler);
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
        startServer(new MultiPartFormValidationHandler(formExpectations, defaultCharset));

        try (Socket client = new Socket(serverURI.getHost(), serverURI.getPort());
             OutputStream output = client.getOutputStream();
             InputStream input = client.getInputStream())
        {
            sendRequest(formRequest, formExpectations, output);

            HttpTester.Response response = HttpTester.parseResponse(input);
            assertThat(response.getStatus(), is(200));
        }
    }

    private void sendRequest(MultiPartRequest formRequest, MultiPartExpectations formExpectations, OutputStream output) throws IOException
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
    }

    public static class CaptureRawMultiPartFormHandler extends Handler.Abstract
    {
        private Path tempDir;

        @Override
        protected void doStart() throws Exception
        {
            super.doStart();
            tempDir = getServer().getTempDirectory().toPath();
        }

        @Override
        public boolean handle(Request request, Response response, Callback callback) throws Exception
        {
            String name = Request.getPathInContext(request);
            int idx = name.lastIndexOf("/");
            if (idx > 0)
                name = name.substring(idx + 1);
            Path outputPath = tempDir.resolve(name);

            System.err.println("Writing: " + outputPath);
            try (InputStream input = Content.Source.asInputStream(request);
                 OutputStream output = Files.newOutputStream(outputPath))
            {
                IO.copy(input, output);

                response.setStatus(200);
                response.getHeaders().put(HttpHeader.CONTENT_TYPE, "text/plain;charset=utf-8");
                Content.Sink.write(response, true, "Success", callback);
                return true;
            }
        }
    }

    public static class MultiPartFormValidationHandler extends Handler.Abstract
    {
        private final MultiPartExpectations multiPartExpectations;
        private final Charset defaultCharset;
        private Path filesDir;

        public MultiPartFormValidationHandler(MultiPartExpectations expectations, Charset defaultCharset)
        {
            this.multiPartExpectations = expectations;
            this.defaultCharset = defaultCharset;
        }

        @Override
        protected void doStart() throws Exception
        {
            super.doStart();
            filesDir = getServer().getTempDirectory().toPath();
        }

        @Override
        public boolean handle(Request request, Response response, Callback callback) throws Exception
        {
            try
            {
                String contentType = request.getHeaders().get(HttpHeader.CONTENT_TYPE);
                String boundary = MultiPart.extractBoundary(contentType);
                MultiPartFormData.Parser formData = new MultiPartFormData.Parser(boundary);
                formData.setFilesDirectory(filesDir);

                Map<String, List<MultiPart.Part>> form = new HashMap<>();

                // May block waiting for multipart form data.
                try (MultiPartFormData.Parts parts = formData.parse(request).join())
                {
                    multiPartExpectations.assertParts(mapActualResults(parts), defaultCharset);
                }

                response.setStatus(200);
                Content.Sink.write(response, true, "Success", callback);
            }
            catch (Throwable t)
            {
                callback.failed(t);
            }
            return true;
        }
    }

    private static MultiPartResults mapActualResults(final MultiPartFormData.Parts parts)
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
                List<MultiPart.Part> namedParts = parts.getAll(name);

                if (namedParts == null)
                    return null;

                List<PartResult> results = new ArrayList<>();
                for (MultiPart.Part namedPart : namedParts)
                {
                    results.add(new NamedPartResult(namedPart));
                }
                return results;
            }
        };
    }

    private static class NamedPartResult implements MultiPartResults.PartResult
    {
        private final MultiPart.Part namedPart;

        public NamedPartResult(MultiPart.Part namedPart)
        {
            this.namedPart = namedPart;
        }

        @Override
        public String getContentType()
        {
            return namedPart.getHeaders().get(HttpHeader.CONTENT_TYPE);
        }

        @Override
        public ByteBuffer asByteBuffer() throws IOException
        {
            return Content.Source.asByteBuffer(namedPart.newContentSource());
        }

        @Override
        public String asString(Charset charset) throws IOException
        {
            if (charset == null)
                return Content.Source.asString(namedPart.newContentSource());
            else
                return Content.Source.asString(namedPart.newContentSource(), charset);
        }

        @Override
        public String getFileName()
        {
            return namedPart.getFileName();
        }

        @Override
        public InputStream asInputStream()
        {
            return Content.Source.asInputStream(namedPart.newContentSource());
        }
    }
}
