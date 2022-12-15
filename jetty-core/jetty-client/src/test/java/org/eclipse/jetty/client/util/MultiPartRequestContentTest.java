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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.client.AbstractHttpClientServerTest;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.MultiPart;
import org.eclipse.jetty.http.MultiPartFormData;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.FutureCallback;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ArgumentsSource;

import static org.eclipse.jetty.toolchain.test.StackUtils.supply;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

// @checkstyle-disable-check : AvoidEscapedUnicodeCharactersCheck
public class MultiPartRequestContentTest extends AbstractHttpClientServerTest
{
    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void testEmptyMultiPart(Scenario scenario) throws Exception
    {
        start(scenario, new AbstractMultiPartHandler()
        {
            @Override
            protected void process(MultiPartFormData.Parts parts)
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
            protected void process(MultiPartFormData.Parts parts)
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
            protected void process(MultiPartFormData.Parts parts) throws Exception
            {
                assertEquals(1, parts.size());
                MultiPart.Part part = parts.iterator().next();
                assertEquals(name, part.getName());
                String contentType = part.getHeaders().get(HttpHeader.CONTENT_TYPE);
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
    public void testFieldDeferred(Scenario scenario) throws Exception
    {
        String name = "field";
        byte[] data = "Hello, World".getBytes(StandardCharsets.US_ASCII);
        start(scenario, new AbstractMultiPartHandler()
        {
            @Override
            protected void process(MultiPartFormData.Parts parts) throws Exception
            {
                assertEquals(1, parts.size());
                MultiPart.Part part = parts.iterator().next();
                assertEquals(name, part.getName());
                assertEquals("text/plain", part.getHeaders().get(HttpHeader.CONTENT_TYPE));
                assertArrayEquals(data, Content.Source.asByteBuffer(part.getContent()).array());
            }
        });

        MultiPartRequestContent multiPart = new MultiPartRequestContent();
        AsyncRequestContent partContent = new AsyncRequestContent("text/plain");
        multiPart.addPart(new MultiPart.ContentSourcePart(name, "file", null, partContent));
        multiPart.close();
        CountDownLatch commitLatch = new CountDownLatch(1);
        CountDownLatch responseLatch = new CountDownLatch(1);
        client.newRequest("localhost", connector.getLocalPort())
            .scheme(scenario.getScheme())
            .method(HttpMethod.POST)
            .body(multiPart)
            .onRequestCommit(r -> commitLatch.countDown())
            .send(result ->
            {
                assertTrue(result.isSucceeded(), supply(result.getFailure()));
                assertEquals(200, result.getResponse().getStatus());
                responseLatch.countDown();
            });

        // Wait until the request has been sent.
        assertTrue(commitLatch.await(5, TimeUnit.SECONDS));

        // Provide the part content.
        FutureCallback fc = new FutureCallback();
        partContent.write(ByteBuffer.wrap(data), fc);
        fc.get();
        partContent.close();

        assertTrue(responseLatch.await(5, TimeUnit.SECONDS));
    }

    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void testFileFromInputStream(Scenario scenario) throws Exception
    {
        String name = "file";
        String fileName = "upload.png";
        String contentType = "image/png";
        byte[] data = randomBytes(512);
        start(scenario, new AbstractMultiPartHandler()
        {
            @Override
            protected void process(MultiPartFormData.Parts parts) throws Exception
            {
                assertEquals(1, parts.size());
                MultiPart.Part part = parts.iterator().next();
                assertEquals(name, part.getName());
                assertEquals(contentType, part.getHeaders().get(HttpHeader.CONTENT_TYPE));
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
            protected void process(MultiPartFormData.Parts parts) throws Exception
            {
                assertEquals(1, parts.size());
                MultiPart.Part part = parts.iterator().next();
                assertEquals(name, part.getName());
                assertEquals(contentType, part.getHeaders().get(HttpHeader.CONTENT_TYPE));
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
    public void testFieldWithFile(Scenario scenario) throws Exception
    {
        // Prepare a file to upload.
        byte[] data = randomBytes(1024);
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
            protected void process(MultiPartFormData.Parts parts) throws Exception
            {
                assertEquals(2, parts.size());
                MultiPart.Part fieldPart = parts.get(0);
                MultiPart.Part filePart = parts.get(1);

                assertEquals(field, fieldPart.getName());
                assertEquals(contentType, fieldPart.getHeaders().get(HttpHeader.CONTENT_TYPE));
                assertEquals(value, Content.Source.asString(fieldPart.getContent(), encoding));
                assertEquals(headerValue, fieldPart.getHeaders().get(headerName));

                assertEquals(fileField, filePart.getName());
                assertEquals("application/octet-stream", filePart.getHeaders().get(HttpHeader.CONTENT_TYPE));
                assertEquals(tmpPath.getFileName().toString(), filePart.getFileName());
                assertEquals(Files.size(tmpPath), filePart.getContent().getLength());
                assertArrayEquals(data, Content.Source.asByteBuffer(filePart.getContent()).array());
            }
        });

        MultiPartRequestContent multiPart = new MultiPartRequestContent();
        HttpFields.Mutable fields = HttpFields.build();
        fields.put(headerName, headerValue);
        multiPart.addPart(new MultiPart.ContentSourcePart(field, "file", fields, new StringRequestContent(value, encoding)));
        multiPart.addPart(new MultiPart.ContentSourcePart(fileField, tmpPath.getFileName().toString(), null, new PathRequestContent(tmpPath)));
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
    public void testFieldDeferredAndFileDeferred(Scenario scenario) throws Exception
    {
        String value = "text";
        Charset encoding = StandardCharsets.US_ASCII;
        byte[] fileData = randomBytes(1024);
        start(scenario, new AbstractMultiPartHandler()
        {
            @Override
            protected void process(MultiPartFormData.Parts parts) throws Exception
            {
                assertEquals(2, parts.size());
                MultiPart.Part fieldPart = parts.get(0);
                MultiPart.Part filePart = parts.get(1);

                assertEquals(value, Content.Source.asString(fieldPart.getContent(), encoding));
                assertEquals("file", filePart.getName());
                assertEquals("application/octet-stream", filePart.getHeaders().get(HttpHeader.CONTENT_TYPE));
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

        CountDownLatch commitLatch = new CountDownLatch(1);
        CountDownLatch responseLatch = new CountDownLatch(1);
        client.newRequest("localhost", connector.getLocalPort())
            .scheme(scenario.getScheme())
            .method(HttpMethod.POST)
            .body(multiPart)
            .onRequestCommit(r -> commitLatch.countDown())
            .send(result ->
            {
                assertTrue(result.isSucceeded(), supply(result.getFailure()));
                assertEquals(200, result.getResponse().getStatus());
                responseLatch.countDown();
            });

        // Wait until the request has been sent.
        assertTrue(commitLatch.await(5, TimeUnit.SECONDS));

        // Provide the part content, in reversed part order.
        fileContent.write(ByteBuffer.wrap(fileData), Callback.NOOP);
        fileContent.close();

        FutureCallback fc = new FutureCallback();
        fieldContent.write(encoding.encode(value), fc);
        fc.get();
        fieldContent.close();

        assertTrue(responseLatch.await(5, TimeUnit.SECONDS));
    }

    private byte[] randomBytes(int length)
    {
        byte[] bytes = new byte[length];
        ThreadLocalRandom.current().nextBytes(bytes);
        // Make sure the last 2 bytes are not \r\n,
        // otherwise the multipart parser gets confused.
        bytes[length - 2] = 0;
        bytes[length - 1] = 0;
        return bytes;
    }

    private abstract static class AbstractMultiPartHandler extends Handler.Abstract
    {
        @Override
        public boolean process(Request request, Response response, Callback callback) throws Exception
        {
            // TODO use the DelayedHandler.UntilMultiPartFormData

            Path tmpDir = MavenTestingUtils.getTargetTestingPath();
            String contentType = request.getHeaders().get(HttpHeader.CONTENT_TYPE);
            assertEquals("multipart/form-data", HttpField.valueParameters(contentType, null));
            String boundary = MultiPart.extractBoundary(contentType);
            MultiPartFormData formData = new MultiPartFormData(boundary);
            formData.setFilesDirectory(tmpDir);
            formData.parse(request);
            try
            {
                process(formData.join()); // May block waiting for multipart form data.
                response.write(true, BufferUtil.EMPTY_BUFFER, callback);
            }
            catch (Exception x)
            {
                Response.writeError(request, response, callback, x);
            }
            return true;
        }

        protected abstract void process(MultiPartFormData.Parts parts) throws Exception;
    }
}
