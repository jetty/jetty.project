//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

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
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.junit.Assert;
import org.junit.Test;

public class MultiPartContentProviderTest extends AbstractHttpClientServerTest
{
    public MultiPartContentProviderTest(SslContextFactory sslContextFactory)
    {
        super(sslContextFactory);
    }

    @Test
    public void testEmptyMultiPart() throws Exception
    {
        start(new AbstractMultiPartHandler()
        {
            @Override
            protected void handle(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
            {
                Collection<Part> parts = request.getParts();
                Assert.assertEquals(0, parts.size());
            }
        });

        MultiPartContentProvider multiPart = new MultiPartContentProvider();
        multiPart.close();
        ContentResponse response = client.newRequest("localhost", connector.getLocalPort())
                .scheme(scheme)
                .method(HttpMethod.POST)
                .content(multiPart)
                .send();

        Assert.assertEquals(200, response.getStatus());
    }

    @Test
    public void testSimpleField() throws Exception
    {
        String name = "field";
        String value = "value";
        start(new AbstractMultiPartHandler()
        {
            @Override
            protected void handle(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
            {
                Collection<Part> parts = request.getParts();
                Assert.assertEquals(1, parts.size());
                Part part = parts.iterator().next();
                Assert.assertEquals(name, part.getName());
                Assert.assertEquals(value, IO.toString(part.getInputStream()));
            }
        });

        MultiPartContentProvider multiPart = new MultiPartContentProvider();
        multiPart.addFieldPart(name, new StringContentProvider(value), null);
        multiPart.close();
        ContentResponse response = client.newRequest("localhost", connector.getLocalPort())
                .scheme(scheme)
                .method(HttpMethod.POST)
                .content(multiPart)
                .send();

        Assert.assertEquals(200, response.getStatus());
    }

    @Test
    public void testFieldWithOverridenContentType() throws Exception
    {
        String name = "field";
        String value = "\u00e8";
        Charset encoding = StandardCharsets.ISO_8859_1;
        start(new AbstractMultiPartHandler()
        {
            @Override
            protected void handle(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
            {
                Collection<Part> parts = request.getParts();
                Assert.assertEquals(1, parts.size());
                Part part = parts.iterator().next();
                Assert.assertEquals(name, part.getName());
                String contentType = part.getContentType();
                Assert.assertNotNull(contentType);
                int equal = contentType.lastIndexOf('=');
                Charset charset = Charset.forName(contentType.substring(equal + 1));
                Assert.assertEquals(encoding, charset);
                Assert.assertEquals(value, IO.toString(part.getInputStream(), charset));
            }
        });

        MultiPartContentProvider multiPart = new MultiPartContentProvider();
        HttpFields fields = new HttpFields();
        fields.put(HttpHeader.CONTENT_TYPE, "text/plain;charset=" + encoding.name());
        BytesContentProvider content = new BytesContentProvider(value.getBytes(encoding));
        multiPart.addFieldPart(name, content, fields);
        multiPart.close();
        ContentResponse response = client.newRequest("localhost", connector.getLocalPort())
                .scheme(scheme)
                .method(HttpMethod.POST)
                .content(multiPart)
                .send();

        Assert.assertEquals(200, response.getStatus());
    }

    @Test
    public void testFieldDeferred() throws Exception
    {
        String name = "field";
        byte[] data = "Hello, World".getBytes(StandardCharsets.US_ASCII);
        start(new AbstractMultiPartHandler()
        {
            @Override
            protected void handle(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
            {
                Collection<Part> parts = request.getParts();
                Assert.assertEquals(1, parts.size());
                Part part = parts.iterator().next();
                Assert.assertEquals(name, part.getName());
                Assert.assertEquals("text/plain", part.getContentType());
                Assert.assertArrayEquals(data, IO.readBytes(part.getInputStream()));
            }
        });

        MultiPartContentProvider multiPart = new MultiPartContentProvider();
        DeferredContentProvider content = new DeferredContentProvider();
        multiPart.addFieldPart(name, content, null);
        multiPart.close();
        CountDownLatch responseLatch = new CountDownLatch(1);
        client.newRequest("localhost", connector.getLocalPort())
                .scheme(scheme)
                .method(HttpMethod.POST)
                .content(multiPart)
                .send(result ->
                {
                    Assert.assertTrue(String.valueOf(result.getFailure()), result.isSucceeded());
                    Assert.assertEquals(200, result.getResponse().getStatus());
                    responseLatch.countDown();
                });

        // Wait until the request has been sent.
        Thread.sleep(1000);

        // Provide the content.
        content.offer(ByteBuffer.wrap(data));
        content.close();

        Assert.assertTrue(responseLatch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testFileFromInputStream() throws Exception
    {
        String name = "file";
        String fileName = "upload.png";
        String contentType = "image/png";
        byte[] data = new byte[512];
        new Random().nextBytes(data);
        start(new AbstractMultiPartHandler()
        {
            @Override
            protected void handle(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
            {
                Collection<Part> parts = request.getParts();
                Assert.assertEquals(1, parts.size());
                Part part = parts.iterator().next();
                Assert.assertEquals(name, part.getName());
                Assert.assertEquals(contentType, part.getContentType());
                Assert.assertEquals(fileName, part.getSubmittedFileName());
                Assert.assertEquals(data.length, part.getSize());
                Assert.assertArrayEquals(data, IO.readBytes(part.getInputStream()));
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
                .scheme(scheme)
                .method(HttpMethod.POST)
                .content(multiPart)
                .send();

        Assert.assertTrue(closeLatch.await(5, TimeUnit.SECONDS));
        Assert.assertEquals(200, response.getStatus());
    }

    @Test
    public void testFileFromPath() throws Exception
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
        start(new AbstractMultiPartHandler()
        {
            @Override
            protected void handle(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
            {
                Collection<Part> parts = request.getParts();
                Assert.assertEquals(1, parts.size());
                Part part = parts.iterator().next();
                Assert.assertEquals(name, part.getName());
                Assert.assertEquals(contentType, part.getContentType());
                Assert.assertEquals(tmpPath.getFileName().toString(), part.getSubmittedFileName());
                Assert.assertEquals(Files.size(tmpPath), part.getSize());
                Assert.assertEquals(data, IO.toString(part.getInputStream(), encoding));
            }
        });

        MultiPartContentProvider multiPart = new MultiPartContentProvider();
        PathContentProvider content = new PathContentProvider(contentType, tmpPath);
        content.setByteBufferPool(client.getByteBufferPool());
        multiPart.addFilePart(name, tmpPath.getFileName().toString(), content, null);
        multiPart.close();
        ContentResponse response = client.newRequest("localhost", connector.getLocalPort())
                .scheme(scheme)
                .method(HttpMethod.POST)
                .content(multiPart)
                .send();

        Assert.assertEquals(200, response.getStatus());

        Files.delete(tmpPath);
    }

    @Test
    public void testFieldWithFile() throws Exception
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
        start(new AbstractMultiPartHandler()
        {
            @Override
            protected void handle(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
            {
                List<Part> parts = new ArrayList<>(request.getParts());
                Assert.assertEquals(2, parts.size());
                Part fieldPart = parts.get(0);
                Part filePart = parts.get(1);
                if (!field.equals(fieldPart.getName()))
                {
                    Part swap = filePart;
                    filePart = fieldPart;
                    fieldPart = swap;
                }

                Assert.assertEquals(field, fieldPart.getName());
                Assert.assertEquals(contentType, fieldPart.getContentType());
                Assert.assertEquals(value, IO.toString(fieldPart.getInputStream(), encoding));
                Assert.assertEquals(headerValue, fieldPart.getHeader(headerName));

                Assert.assertEquals(fileField, filePart.getName());
                Assert.assertEquals("application/octet-stream", filePart.getContentType());
                Assert.assertEquals(tmpPath.getFileName().toString(), filePart.getSubmittedFileName());
                Assert.assertEquals(Files.size(tmpPath), filePart.getSize());
                Assert.assertArrayEquals(data, IO.readBytes(filePart.getInputStream()));
            }
        });

        MultiPartContentProvider multiPart = new MultiPartContentProvider();
        HttpFields fields = new HttpFields();
        fields.put(headerName, headerValue);
        multiPart.addFieldPart(field, new StringContentProvider(value, encoding), fields);
        multiPart.addFilePart(fileField, tmpPath.getFileName().toString(), new PathContentProvider(tmpPath), null);
        multiPart.close();
        ContentResponse response = client.newRequest("localhost", connector.getLocalPort())
                .scheme(scheme)
                .method(HttpMethod.POST)
                .content(multiPart)
                .send();

        Assert.assertEquals(200, response.getStatus());

        Files.delete(tmpPath);
    }

    @Test
    public void testFieldDeferredAndFileDeferred() throws Exception
    {
        String value = "text";
        Charset encoding = StandardCharsets.US_ASCII;
        byte[] fileData = new byte[1024];
        new Random().nextBytes(fileData);
        start(new AbstractMultiPartHandler()
        {
            @Override
            protected void handle(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
            {
                List<Part> parts = new ArrayList<>(request.getParts());
                Assert.assertEquals(2, parts.size());
                Part fieldPart = parts.get(0);
                Part filePart = parts.get(1);
                if (!"field".equals(fieldPart.getName()))
                {
                    Part swap = filePart;
                    filePart = fieldPart;
                    fieldPart = swap;
                }

                Assert.assertEquals(value, IO.toString(fieldPart.getInputStream(), encoding));

                Assert.assertEquals("file", filePart.getName());
                Assert.assertEquals("application/octet-stream", filePart.getContentType());
                Assert.assertEquals("fileName", filePart.getSubmittedFileName());
                Assert.assertArrayEquals(fileData, IO.readBytes(filePart.getInputStream()));
            }
        });

        MultiPartContentProvider multiPart = new MultiPartContentProvider();
        DeferredContentProvider fieldContent = new DeferredContentProvider();
        multiPart.addFieldPart("field", fieldContent, null);
        DeferredContentProvider fileContent = new DeferredContentProvider();
        multiPart.addFilePart("file", "fileName", fileContent, null);
        CountDownLatch responseLatch = new CountDownLatch(1);
        client.newRequest("localhost", connector.getLocalPort())
                .scheme(scheme)
                .method(HttpMethod.POST)
                .content(multiPart)
                .send(result ->
                {
                    Assert.assertTrue(String.valueOf(result.getFailure()), result.isSucceeded());
                    Assert.assertEquals(200, result.getResponse().getStatus());
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

        Assert.assertTrue(responseLatch.await(5, TimeUnit.SECONDS));
    }

    private static abstract class AbstractMultiPartHandler extends AbstractHandler
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
}
