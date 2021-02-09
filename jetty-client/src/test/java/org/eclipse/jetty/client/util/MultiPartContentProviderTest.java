//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.client.util;

import java.io.BufferedWriter;
import java.io.ByteArrayInputStream;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.servlet.MultipartConfigElement;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.Part;

import org.eclipse.jetty.client.AbstractHttpClientServerTest;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.util.IO;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ArgumentsSource;

import static org.eclipse.jetty.toolchain.test.StackUtils.supply;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

// @checkstyle-disable-check : AvoidEscapedUnicodeCharactersCheck
public class MultiPartContentProviderTest extends AbstractHttpClientServerTest
{
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

        MultiPartContentProvider multiPart = new MultiPartContentProvider();
        multiPart.close();
        ContentResponse response = client.newRequest("localhost", connector.getLocalPort())
            .scheme(scenario.getScheme())
            .method(HttpMethod.POST)
            .content(multiPart)
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

        MultiPartContentProvider multiPart = new MultiPartContentProvider();
        multiPart.addFieldPart(name, new StringContentProvider(value), null);
        multiPart.close();
        ContentResponse response = client.newRequest("localhost", connector.getLocalPort())
            .scheme(scenario.getScheme())
            .method(HttpMethod.POST)
            .content(multiPart)
            .send();

        assertEquals(200, response.getStatus());
    }

    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void testFieldWithOverridenContentType(Scenario scenario) throws Exception
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

        MultiPartContentProvider multiPart = new MultiPartContentProvider();
        HttpFields fields = new HttpFields();
        fields.put(HttpHeader.CONTENT_TYPE, "text/plain;charset=" + encoding.name());
        BytesContentProvider content = new BytesContentProvider(value.getBytes(encoding));
        multiPart.addFieldPart(name, content, fields);
        multiPart.close();
        ContentResponse response = client.newRequest("localhost", connector.getLocalPort())
            .scheme(scenario.getScheme())
            .method(HttpMethod.POST)
            .content(multiPart)
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

        MultiPartContentProvider multiPart = new MultiPartContentProvider();
        DeferredContentProvider content = new DeferredContentProvider();
        multiPart.addFieldPart(name, content, null);
        multiPart.close();
        CountDownLatch responseLatch = new CountDownLatch(1);
        client.newRequest("localhost", connector.getLocalPort())
            .scheme(scenario.getScheme())
            .method(HttpMethod.POST)
            .content(multiPart)
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
        MultiPartContentProvider multiPart = new MultiPartContentProvider();
        InputStreamContentProvider content = new InputStreamContentProvider(new ByteArrayInputStream(data)
        {
            @Override
            public void close() throws IOException
            {
                super.close();
                closeLatch.countDown();
            }
        });
        HttpFields fields = new HttpFields();
        fields.put(HttpHeader.CONTENT_TYPE, contentType);
        multiPart.addFilePart(name, fileName, content, fields);
        multiPart.close();
        ContentResponse response = client.newRequest("localhost", connector.getLocalPort())
            .scheme(scenario.getScheme())
            .method(HttpMethod.POST)
            .content(multiPart)
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

        MultiPartContentProvider multiPart = new MultiPartContentProvider();
        PathContentProvider content = new PathContentProvider(contentType, tmpPath);
        content.setByteBufferPool(client.getByteBufferPool());
        multiPart.addFilePart(name, tmpPath.getFileName().toString(), content, null);
        multiPart.close();
        ContentResponse response = client.newRequest("localhost", connector.getLocalPort())
            .scheme(scenario.getScheme())
            .method(HttpMethod.POST)
            .content(multiPart)
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

        MultiPartContentProvider multiPart = new MultiPartContentProvider();
        HttpFields fields = new HttpFields();
        fields.put(headerName, headerValue);
        multiPart.addFieldPart(field, new StringContentProvider(value, encoding), fields);
        multiPart.addFilePart(fileField, tmpPath.getFileName().toString(), new PathContentProvider(tmpPath), null);
        multiPart.close();
        ContentResponse response = client.newRequest("localhost", connector.getLocalPort())
            .scheme(scenario.getScheme())
            .method(HttpMethod.POST)
            .content(multiPart)
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

        MultiPartContentProvider multiPart = new MultiPartContentProvider();
        DeferredContentProvider fieldContent = new DeferredContentProvider();
        multiPart.addFieldPart("field", fieldContent, null);
        DeferredContentProvider fileContent = new DeferredContentProvider();
        multiPart.addFilePart("file", "fileName", fileContent, null);
        CountDownLatch responseLatch = new CountDownLatch(1);
        client.newRequest("localhost", connector.getLocalPort())
            .scheme(scenario.getScheme())
            .method(HttpMethod.POST)
            .content(multiPart)
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

        multiPart.close();

        assertTrue(responseLatch.await(5, TimeUnit.SECONDS));
    }

    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void testEachPartIsClosed(Scenario scenario) throws Exception
    {
        String name1 = "field1";
        String value1 = "value1";
        String name2 = "field2";
        String value2 = "value2";
        start(scenario, new AbstractMultiPartHandler()
        {
            @Override
            protected void handle(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
            {
                Collection<Part> parts = request.getParts();
                assertEquals(2, parts.size());
                Iterator<Part> iterator = parts.iterator();
                Part part1 = iterator.next();
                assertEquals(name1, part1.getName());
                assertEquals(value1, IO.toString(part1.getInputStream()));
                Part part2 = iterator.next();
                assertEquals(name2, part2.getName());
                assertEquals(value2, IO.toString(part2.getInputStream()));
            }
        });

        AtomicInteger closeCount = new AtomicInteger();
        MultiPartContentProvider multiPart = new MultiPartContentProvider();
        multiPart.addFieldPart(name1, new CloseableStringContentProvider(value1, closeCount::incrementAndGet), null);
        multiPart.addFieldPart(name2, new CloseableStringContentProvider(value2, closeCount::incrementAndGet), null);
        multiPart.close();
        ContentResponse response = client.newRequest("localhost", connector.getLocalPort())
            .scheme(scenario.getScheme())
            .method(HttpMethod.POST)
            .content(multiPart)
            .send();

        assertEquals(200, response.getStatus());
        assertEquals(2, closeCount.get());
    }

    private abstract static class AbstractMultiPartHandler extends AbstractHandler
    {
        @Override
        public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
        {
            baseRequest.setHandled(true);
            File tmpDir = MavenTestingUtils.getTargetTestingDir();
            request.setAttribute(Request.MULTIPART_CONFIG_ELEMENT, new MultipartConfigElement(tmpDir.getAbsolutePath()));
            handle(request, response);
        }

        protected abstract void handle(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException;
    }

    private static class CloseableStringContentProvider extends StringContentProvider
    {
        private final Runnable closeFn;

        private CloseableStringContentProvider(String content, Runnable closeFn)
        {
            super(content);
            this.closeFn = closeFn;
        }

        @Override
        public Iterator<ByteBuffer> iterator()
        {
            return new CloseableIterator<>(super.iterator());
        }

        private class CloseableIterator<T> implements Iterator<T>, Closeable
        {
            private final Iterator<T> iterator;

            public CloseableIterator(Iterator<T> iterator)
            {
                this.iterator = iterator;
            }

            @Override
            public boolean hasNext()
            {
                return iterator.hasNext();
            }

            @Override
            public T next()
            {
                return iterator.next();
            }

            @Override
            public void close()
            {
                closeFn.run();
            }
        }
    }
}
