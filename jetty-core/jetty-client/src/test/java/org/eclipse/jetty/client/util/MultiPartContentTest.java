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

import org.eclipse.jetty.client.AbstractHttpClientServerTest;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.fail;

// TODO
// @checkstyle-disable-check : AvoidEscapedUnicodeCharactersCheck
@Disabled
public class MultiPartContentTest extends AbstractHttpClientServerTest
{
    @Test
    public void testNeedToUpdateThisTest()
    {
        fail("This test needs to be updated to use Core version of multipart (when available)");
    }
/*
    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void testEmptyMultiPart(Scenario scenario) throws Exception
    {
        start(scenario, new AbstractMultiPartHandler()
        {
            @Override
            protected void handle(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
            {
                Collection<Part> parts = request.getParts();
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
            protected void handle(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
            {
                Collection<Part> parts = request.getParts();
                assertEquals(1, parts.size());
                Part part = parts.iterator().next();
                assertEquals(name, part.getName());
                assertEquals(value, IO.toString(part.getInputStream()));
            }
        });

        MultiPartRequestContent multiPart = new MultiPartRequestContent();
        multiPart.addFieldPart(name, new StringRequestContent(value), null);
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
            protected void handle(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
            {
                Collection<Part> parts = request.getParts();
                assertEquals(1, parts.size());
                Part part = parts.iterator().next();
                assertEquals(name, part.getName());
                String contentType = part.getContentType();
                assertNotNull(contentType);
                int equal = contentType.lastIndexOf('=');
                Charset charset = Charset.forName(contentType.substring(equal + 1));
                assertEquals(encoding, charset);
                assertEquals(value, IO.toString(part.getInputStream(), charset));
            }
        });

        MultiPartRequestContent multiPart = new MultiPartRequestContent();
        HttpFields.Mutable fields = HttpFields.build();
        fields.put(HttpHeader.CONTENT_TYPE, "text/plain;charset=" + encoding.name());
        BytesRequestContent content = new BytesRequestContent(value.getBytes(encoding));
        multiPart.addFieldPart(name, content, fields);
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
            protected void handle(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
            {
                Collection<Part> parts = request.getParts();
                assertEquals(1, parts.size());
                Part part = parts.iterator().next();
                assertEquals(name, part.getName());
                assertEquals("text/plain", part.getContentType());
                assertArrayEquals(data, IO.readBytes(part.getInputStream()));
            }
        });

        MultiPartRequestContent multiPart = new MultiPartRequestContent();
        AsyncRequestContent content = new AsyncRequestContent("text/plain");
        multiPart.addFieldPart(name, content, null);
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
        content.offer(ByteBuffer.wrap(data));
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
            protected void handle(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
            {
                Collection<Part> parts = request.getParts();
                assertEquals(1, parts.size());
                Part part = parts.iterator().next();
                assertEquals(name, part.getName());
                assertEquals(contentType, part.getContentType());
                assertEquals(fileName, part.getSubmittedFileName());
                assertEquals(data.length, part.getSize());
                assertArrayEquals(data, IO.readBytes(part.getInputStream()));
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
        multiPart.addFilePart(name, fileName, content, fields);
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
            protected void handle(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
            {
                Collection<Part> parts = request.getParts();
                assertEquals(1, parts.size());
                Part part = parts.iterator().next();
                assertEquals(name, part.getName());
                assertEquals(contentType, part.getContentType());
                assertEquals(tmpPath.getFileName().toString(), part.getSubmittedFileName());
                assertEquals(Files.size(tmpPath), part.getSize());
                assertEquals(data, IO.toString(part.getInputStream(), encoding));
            }
        });

        MultiPartRequestContent multiPart = new MultiPartRequestContent();
        PathRequestContent content = new PathRequestContent(contentType, tmpPath);
        content.setByteBufferPool(client.getByteBufferPool());
        content.setUseDirectByteBuffers(client.isUseOutputDirectByteBuffers());
        multiPart.addFilePart(name, tmpPath.getFileName().toString(), content, null);
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
            protected void handle(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
            {
                List<Part> parts = new ArrayList<>(request.getParts());
                assertEquals(2, parts.size());
                Part fieldPart = parts.get(0);
                Part filePart = parts.get(1);
                if (!field.equals(fieldPart.getName()))
                {
                    Part swap = filePart;
                    filePart = fieldPart;
                    fieldPart = swap;
                }

                assertEquals(field, fieldPart.getName());
                assertEquals(contentType, fieldPart.getContentType());
                assertEquals(value, IO.toString(fieldPart.getInputStream(), encoding));
                assertEquals(headerValue, fieldPart.getHeader(headerName));

                assertEquals(fileField, filePart.getName());
                assertEquals("application/octet-stream", filePart.getContentType());
                assertEquals(tmpPath.getFileName().toString(), filePart.getSubmittedFileName());
                assertEquals(Files.size(tmpPath), filePart.getSize());
                assertArrayEquals(data, IO.readBytes(filePart.getInputStream()));
            }
        });

        MultiPartRequestContent multiPart = new MultiPartRequestContent();
        HttpFields.Mutable fields = HttpFields.build();
        fields.put(headerName, headerValue);
        multiPart.addFieldPart(field, new StringRequestContent(value, encoding), fields);
        multiPart.addFilePart(fileField, tmpPath.getFileName().toString(), new PathRequestContent(tmpPath), null);
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
        byte[] fileData = new byte[1024];
        new Random().nextBytes(fileData);
        start(scenario, new AbstractMultiPartHandler()
        {
            @Override
            protected void handle(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
            {
                List<Part> parts = new ArrayList<>(request.getParts());
                assertEquals(2, parts.size());
                Part fieldPart = parts.get(0);
                Part filePart = parts.get(1);
                if (!"field".equals(fieldPart.getName()))
                {
                    Part swap = filePart;
                    filePart = fieldPart;
                    fieldPart = swap;
                }

                assertEquals(value, IO.toString(fieldPart.getInputStream(), encoding));

                assertEquals("file", filePart.getName());
                assertEquals("application/octet-stream", filePart.getContentType());
                assertEquals("fileName", filePart.getSubmittedFileName());
                assertArrayEquals(fileData, IO.readBytes(filePart.getInputStream()));
            }
        });

        MultiPartRequestContent multiPart = new MultiPartRequestContent();
        AsyncRequestContent fieldContent = new AsyncRequestContent();
        multiPart.addFieldPart("field", fieldContent, null);
        AsyncRequestContent fileContent = new AsyncRequestContent();
        multiPart.addFilePart("file", "fileName", fileContent, null);
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
        fileContent.offer(ByteBuffer.wrap(fileData));
        fileContent.close();

        Thread.sleep(1000);

        fieldContent.offer(encoding.encode(value));
        fieldContent.close();

        assertTrue(responseLatch.await(5, TimeUnit.SECONDS));
    }

    private abstract static class AbstractMultiPartHandler extends AbstractHandler
    {
        @Override
        public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
        {
            baseRequest.setHandled(true);
            File tmpDir = MavenTestingUtils.getTargetTestingDir();
            request.setAttribute(Request.__MULTIPART_CONFIG_ELEMENT, new MultipartConfigElement(tmpDir.getAbsolutePath()));
            handle(request, response);
        }

        protected abstract void handle(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException;
    }
*/
}
