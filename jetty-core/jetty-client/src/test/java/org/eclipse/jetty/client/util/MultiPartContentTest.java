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

package org.eclipse.jetty.client.util;

import java.io.BufferedWriter;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.client.AbstractHttpClientServerTest;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.MultiPart;
import org.eclipse.jetty.http.MultiParts;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.FutureCallback;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ArgumentsSource;

import static org.eclipse.jetty.toolchain.test.StackUtils.supply;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

// @checkstyle-disable-check : AvoidEscapedUnicodeCharactersCheck
public class MultiPartContentTest extends AbstractHttpClientServerTest
{
    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void testEmptyMultiPart(Scenario scenario) throws Exception
    {
        start(scenario, new AbstractMultiPartHandler()
        {
            @Override
            protected void process(MultiParts.Parts parts)
            {
                assertEquals(0, parts.size());
            }
        });

        MultiPartRequestContent multiPart = new MultiPartRequestContent();
        multiPart.close();
        ContentResponse response = client.newRequest("localhost", connector.getLocalPort())
            .scheme(scenario.getScheme())
            .method(HttpMethod.POST)
            .body(multiPart)
            .send();

        assertEquals(200, response.getStatus());
    }

    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void testSimpleField(Scenario scenario) throws Exception
    {
        String name = "field";
        String value = "value";
        start(scenario, new AbstractMultiPartHandler()
        {
            @Override
            protected void process(MultiParts.Parts parts)
            {
                assertEquals(1, parts.size());
                MultiPart.Part part = parts.iterator().next();
                assertEquals(name, part.getName());
                assertEquals(value, part.getContentAsString(null));
            }
        });

        MultiPartRequestContent multiPart = new MultiPartRequestContent();
        multiPart.addPart(new MultiPart.ContentSourcePart(name, null, HttpFields.EMPTY, new StringRequestContent(value)));
        multiPart.close();
        ContentResponse response = client.newRequest("localhost", connector.getLocalPort())
            .scheme(scenario.getScheme())
            .method(HttpMethod.POST)
            .body(multiPart)
            .send();

        assertEquals(200, response.getStatus());
    }

    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void testFieldWithOverriddenContentType(Scenario scenario) throws Exception
    {
        String name = "field";
        String value = "\u00e8";
        Charset encoding = StandardCharsets.ISO_8859_1;
        start(scenario, new AbstractMultiPartHandler()
        {
            @Override
            protected void process(MultiParts.Parts parts) throws Exception
            {
                assertEquals(1, parts.size());
                MultiPart.Part part = parts.iterator().next();
                assertEquals(name, part.getName());
                String contentType = part.getHttpFields().get(HttpHeader.CONTENT_TYPE);
                assertNotNull(contentType);
                int equal = contentType.lastIndexOf('=');
                Charset charset = Charset.forName(contentType.substring(equal + 1));
                assertEquals(encoding, charset);
                assertEquals(value, Content.Source.asString(part.getContent(), charset));
            }
        });

        MultiPartRequestContent multiPart = new MultiPartRequestContent();
        HttpFields.Mutable fields = HttpFields.build();
        fields.put(HttpHeader.CONTENT_TYPE, "text/plain;charset=" + encoding.name());
        BytesRequestContent content = new BytesRequestContent(value.getBytes(encoding));
        multiPart.addPart(new MultiPart.ContentSourcePart(name, "file", fields, content));
        multiPart.close();
        ContentResponse response = client.newRequest("localhost", connector.getLocalPort())
            .scheme(scenario.getScheme())
            .method(HttpMethod.POST)
            .body(multiPart)
            .send();

        assertEquals(200, response.getStatus());
    }

    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    @Disabled("TODO")
    public void testFieldDeferred(Scenario scenario) throws Exception
    {
        String name = "field";
        byte[] data = "Hello, World".getBytes(StandardCharsets.US_ASCII);
        start(scenario, new AbstractMultiPartHandler()
        {
            @Override
            protected void process(MultiParts.Parts parts) throws Exception
            {
                assertEquals(1, parts.size());
                MultiPart.Part part = parts.iterator().next();
                assertEquals(name, part.getName());
                assertEquals("text/plain", part.getHttpFields().get(HttpHeader.CONTENT_TYPE));
                assertArrayEquals(data, Content.Source.asByteBuffer(part.getContent()).array());
            }
        });

        MultiPartRequestContent multiPart = new MultiPartRequestContent();
        AsyncRequestContent content = new AsyncRequestContent("text/plain");
        multiPart.addPart(new MultiPart.ContentSourcePart(name, "file", null, content));
        multiPart.close();
        CountDownLatch responseLatch = new CountDownLatch(1);
        client.newRequest("localhost", connector.getLocalPort())
            .scheme(scenario.getScheme())
            .method(HttpMethod.POST)
            .body(multiPart)
            .send(result ->
            {
                assertTrue(result.isSucceeded(), supply(result.getFailure()));
                assertEquals(200, result.getResponse().getStatus());
                responseLatch.countDown();
            });

        // Wait until the request has been sent.
        Thread.sleep(1000);

        // Provide the content.
        FutureCallback fc = new FutureCallback();
        content.write(ByteBuffer.wrap(data), fc);
        fc.get();
        content.close();

        assertTrue(responseLatch.await(5, TimeUnit.SECONDS));
    }

    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void testFileFromInputStream(Scenario scenario) throws Exception
    {
        String name = "file";
        String fileName = "upload.png";
        String contentType = "image/png";
        byte[] data = new byte[512];
        new Random().nextBytes(data);
        start(scenario, new AbstractMultiPartHandler()
        {
            @Override
            protected void process(MultiParts.Parts parts) throws Exception
            {
                assertEquals(1, parts.size());
                MultiPart.Part part = parts.iterator().next();
                assertEquals(name, part.getName());
                assertEquals(contentType, part.getHttpFields().get(HttpHeader.CONTENT_TYPE));
                assertEquals(fileName, part.getFileName());
                assertEquals(data.length, part.getContent().getLength());
                assertArrayEquals(data, Content.Source.asByteBuffer(part.getContent()).array());
            }
        });

        CountDownLatch closeLatch = new CountDownLatch(1);
        MultiPartRequestContent multiPart = new MultiPartRequestContent();
        InputStreamRequestContent content = new InputStreamRequestContent(new ByteArrayInputStream(data)
        {
            @Override
            public void close() throws IOException
            {
                super.close();
                closeLatch.countDown();
            }
        });
        HttpFields.Mutable fields = HttpFields.build();
        fields.put(HttpHeader.CONTENT_TYPE, contentType);
        multiPart.addPart(new MultiPart.ContentSourcePart(name, fileName, fields, content));
        multiPart.close();
        ContentResponse response = client.newRequest("localhost", connector.getLocalPort())
            .scheme(scenario.getScheme())
            .method(HttpMethod.POST)
            .body(multiPart)
            .send();

        assertTrue(closeLatch.await(5, TimeUnit.SECONDS));
        assertEquals(200, response.getStatus());
    }

    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    @Disabled("TODO")
    public void testFileFromPath(Scenario scenario) throws Exception
    {
        // Prepare a file to upload.
        String data = "multipart_test_\u20ac";
        Path tmpDir = MavenTestingUtils.getTargetTestingPath();
        Path tmpPath = Files.createTempFile(tmpDir, "multipart_", ".txt");
        Charset encoding = StandardCharsets.UTF_8;
        try (BufferedWriter writer = Files.newBufferedWriter(tmpPath, encoding, StandardOpenOption.CREATE))
        {
            writer.write(data);
        }

        String name = "file";
        String contentType = "text/plain; charset=" + encoding.name();
        start(scenario, new AbstractMultiPartHandler()
        {
            @Override
            protected void process(MultiParts.Parts parts) throws Exception
            {
                assertEquals(1, parts.size());
                MultiPart.Part part = parts.iterator().next();
                assertEquals(name, part.getName());
                assertEquals(contentType, part.getHttpFields().get(HttpHeader.CONTENT_TYPE));
                assertEquals(tmpPath.getFileName().toString(), part.getFileName());
                assertEquals(Files.size(tmpPath), part.getContent().getLength());
                assertEquals(data, Content.Source.asString(part.getContent(), encoding));
            }
        });

        MultiPartRequestContent multiPart = new MultiPartRequestContent();
        PathRequestContent content = new PathRequestContent(contentType, tmpPath, client.getByteBufferPool().asRetainableByteBufferPool());
        content.setUseDirectByteBuffers(client.isUseOutputDirectByteBuffers());
        multiPart.addPart(new MultiPart.ContentSourcePart(name, tmpPath.getFileName().toString(), null, content));
        multiPart.close();
        ContentResponse response = client.newRequest("localhost", connector.getLocalPort())
            .scheme(scenario.getScheme())
            .method(HttpMethod.POST)
            .body(multiPart)
            .send();

        assertEquals(200, response.getStatus());

        Files.delete(tmpPath);
    }

    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    @Disabled("TODO")
    public void testFieldWithFile(Scenario scenario) throws Exception
    {
        // Prepare a file to upload.
        byte[] data = new byte[1024];
        new Random().nextBytes(data);
        Path tmpDir = MavenTestingUtils.getTargetTestingPath();
        Path tmpPath = Files.createTempFile(tmpDir, "multipart_", ".txt");
        try (OutputStream output = Files.newOutputStream(tmpPath, StandardOpenOption.CREATE))
        {
            output.write(data);
        }

        String field = "field";
        String value = "\u20ac";
        String fileField = "file";
        Charset encoding = StandardCharsets.UTF_8;
        String contentType = "text/plain;charset=" + encoding.name();
        String headerName = "foo";
        String headerValue = "bar";
        start(scenario, new AbstractMultiPartHandler()
        {
            @Override
            protected void process(MultiParts.Parts parts) throws Exception
            {
                assertEquals(2, parts.size());
                MultiPart.Part fieldPart = parts.get(0);
                MultiPart.Part filePart = parts.get(1);
                if (!field.equals(fieldPart.getName()))
                {
                    MultiPart.Part swap = filePart;
                    filePart = fieldPart;
                    fieldPart = swap;
                }

                assertEquals(field, fieldPart.getName());
                assertEquals(contentType, fieldPart.getHttpFields().get(HttpHeader.CONTENT_TYPE));
                assertEquals(value, Content.Source.asString(fieldPart.getContent(), encoding));
                assertEquals(headerValue, fieldPart.getHttpFields().get(headerName));

                assertEquals(fileField, filePart.getName());
                assertEquals("application/octet-stream", filePart.getHttpFields().get(HttpHeader.CONTENT_TYPE));
                assertEquals(tmpPath.getFileName().toString(), filePart.getFileName());
                assertEquals(Files.size(tmpPath), filePart.getContent().getLength());
                assertArrayEquals(data, Content.Source.asByteBuffer(filePart.getContent()).array());
            }
        });

        MultiPartRequestContent multiPart = new MultiPartRequestContent();
        HttpFields.Mutable fields = HttpFields.build();
        fields.put(headerName, headerValue);
        multiPart.addPart(new MultiPart.ByteBufferPart(field, "file", fields, ByteBuffer.wrap(value.getBytes(encoding))));
        multiPart.addPart(new MultiPart.PathPart(fileField, tmpPath.getFileName().toString(), null, tmpPath));
        multiPart.close();
        ContentResponse response = client.newRequest("localhost", connector.getLocalPort())
            .scheme(scenario.getScheme())
            .method(HttpMethod.POST)
            .body(multiPart)
            .send();

        assertEquals(200, response.getStatus());

        Files.delete(tmpPath);
    }

    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    @Disabled("TODO")
    public void testFieldDeferredAndFileDeferred(Scenario scenario) throws Exception
    {
        String value = "text";
        Charset encoding = StandardCharsets.US_ASCII;
        byte[] fileData = new byte[1024];
        new Random().nextBytes(fileData);
        start(scenario, new AbstractMultiPartHandler()
        {
            @Override
            protected void process(MultiParts.Parts parts) throws Exception
            {
                assertEquals(2, parts.size());
                MultiPart.Part fieldPart = parts.get(0);
                MultiPart.Part filePart = parts.get(1);
                if (!"field".equals(fieldPart.getName()))
                {
                    MultiPart.Part swap = filePart;
                    filePart = fieldPart;
                    fieldPart = swap;
                }

                assertEquals(value, Content.Source.asString(fieldPart.getContent(), encoding));

                assertEquals("file", filePart.getName());
                assertEquals("application/octet-stream", filePart.getHttpFields().get(HttpHeader.CONTENT_TYPE));
                assertEquals("fileName", filePart.getFileName());
                assertArrayEquals(fileData, Content.Source.asByteBuffer(filePart.getContent()).array());
            }
        });

        MultiPartRequestContent multiPart = new MultiPartRequestContent();
        AsyncRequestContent fieldContent = new AsyncRequestContent();
        multiPart.addPart(new MultiPart.ContentSourcePart("field", "file", null, fieldContent));
        AsyncRequestContent fileContent = new AsyncRequestContent();
        multiPart.addPart(new MultiPart.ContentSourcePart("file", "fileName", null, fileContent));
        multiPart.close();

        CountDownLatch responseLatch = new CountDownLatch(1);
        client.newRequest("localhost", connector.getLocalPort())
            .scheme(scenario.getScheme())
            .method(HttpMethod.POST)
            .body(multiPart)
            .send(result ->
            {
                assertTrue(result.isSucceeded(), supply(result.getFailure()));
                assertEquals(200, result.getResponse().getStatus());
                responseLatch.countDown();
            });

        // Wait until the request has been sent.
        Thread.sleep(1000);

        // Provide the content, in reversed part order.
        FutureCallback fc = new FutureCallback();
        fileContent.write(ByteBuffer.wrap(fileData), fc);
        fc.get();
        fileContent.close();

        Thread.sleep(1000);

        fc = new FutureCallback();
        fieldContent.write(encoding.encode(value), fc);
        fc.get();
        fieldContent.close();

        assertTrue(responseLatch.await(5, TimeUnit.SECONDS));
    }

    private abstract static class AbstractMultiPartHandler extends Handler.Processor
    {
        @Override
        public void process(Request request, Response response, Callback callback) throws Exception
        {
            Path tmpDir = MavenTestingUtils.getTargetTestingPath();
            String contentType = request.getHeaders().get(HttpHeader.CONTENT_TYPE);
            assertEquals("multipart/form-data", HttpField.valueParameters(contentType, null));
            String boundary = MultiParts.extractBoundary(contentType);
            MultiParts multiParts = new MultiParts(boundary);
            multiParts.setFileDirectory(tmpDir);
            multiParts.parse(request);
            try
            {
                process(multiParts.join());
                response.write(true, BufferUtil.EMPTY_BUFFER, callback);
            }
            catch (Exception x)
            {
                Response.writeError(request, response, callback, x);
            }
        }

        protected abstract void process(MultiParts.Parts parts) throws Exception;
    }
}
