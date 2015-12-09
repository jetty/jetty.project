//
//  ========================================================================
//  Copyright (c) 1995-2015 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.client;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPOutputStream;

import javax.servlet.ServletException;
import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.junit.Assert;
import org.junit.Test;

public class HttpClientGZIPTest extends AbstractHttpClientServerTest
{
    public HttpClientGZIPTest(SslContextFactory sslContextFactory)
    {
        super(sslContextFactory);
    }

    @Test
    public void testGZIPContentEncoding() throws Exception
    {
        final byte[] data = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9};
        start(new AbstractHandler()
        {
            @Override
            public void handle(String target, org.eclipse.jetty.server.Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                baseRequest.setHandled(true);
                response.setHeader("Content-Encoding", "gzip");
                GZIPOutputStream gzipOutput = new GZIPOutputStream(response.getOutputStream());
                gzipOutput.write(data);
                gzipOutput.finish();
            }
        });

        ContentResponse response = client.newRequest("localhost", connector.getLocalPort())
                .scheme(scheme)
                .timeout(5, TimeUnit.SECONDS)
                .send();

        Assert.assertEquals(200, response.getStatus());
        Assert.assertArrayEquals(data, response.getContent());
    }

    @Test
    public void testGZIPContentOneByteAtATime() throws Exception
    {
        final byte[] data = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9};
        start(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                baseRequest.setHandled(true);
                response.setHeader("Content-Encoding", "gzip");

                ByteArrayOutputStream gzipData = new ByteArrayOutputStream();
                GZIPOutputStream gzipOutput = new GZIPOutputStream(gzipData);
                gzipOutput.write(data);
                gzipOutput.finish();

                ServletOutputStream output = response.getOutputStream();
                byte[] gzipBytes = gzipData.toByteArray();
                for (byte gzipByte : gzipBytes)
                {
                    output.write(gzipByte);
                    output.flush();
                    sleep(100);
                }
            }
        });

        ContentResponse response = client.newRequest("localhost", connector.getLocalPort())
                .scheme(scheme)
                .send();

        Assert.assertEquals(200, response.getStatus());
        Assert.assertArrayEquals(data, response.getContent());
    }

    @Test
    public void testGZIPContentSentTwiceInOneWrite() throws Exception
    {
        final byte[] data = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9};
        start(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                baseRequest.setHandled(true);
                response.setHeader("Content-Encoding", "gzip");

                ByteArrayOutputStream gzipData = new ByteArrayOutputStream();
                GZIPOutputStream gzipOutput = new GZIPOutputStream(gzipData);
                gzipOutput.write(data);
                gzipOutput.finish();

                byte[] gzipBytes = gzipData.toByteArray();
                byte[] content = Arrays.copyOf(gzipBytes, 2 * gzipBytes.length);
                System.arraycopy(gzipBytes, 0, content, gzipBytes.length, gzipBytes.length);

                ServletOutputStream output = response.getOutputStream();
                output.write(content);
            }
        });

        ContentResponse response = client.newRequest("localhost", connector.getLocalPort())
                .scheme(scheme)
                .send();

        Assert.assertEquals(200, response.getStatus());

        byte[] expected = Arrays.copyOf(data, 2 * data.length);
        System.arraycopy(data, 0, expected, data.length, data.length);
        Assert.assertArrayEquals(expected, response.getContent());
    }

    @Test
    public void testGZIPContentFragmentedBeforeTrailer() throws Exception
    {
        // There are 8 trailer bytes to gzip encoding.
        testGZIPContentFragmented(9);
    }

    @Test
    public void testGZIPContentFragmentedAtTrailer() throws Exception
    {
        // There are 8 trailer bytes to gzip encoding.
        testGZIPContentFragmented(1);
    }

    private void testGZIPContentFragmented(final int fragment) throws Exception
    {
        final byte[] data = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9};
        start(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                baseRequest.setHandled(true);
                response.setHeader("Content-Encoding", "gzip");

                ByteArrayOutputStream gzipData = new ByteArrayOutputStream();
                GZIPOutputStream gzipOutput = new GZIPOutputStream(gzipData);
                gzipOutput.write(data);
                gzipOutput.finish();

                byte[] gzipBytes = gzipData.toByteArray();
                byte[] chunk1 = Arrays.copyOfRange(gzipBytes, 0, gzipBytes.length - fragment);
                byte[] chunk2 = Arrays.copyOfRange(gzipBytes, gzipBytes.length - fragment, gzipBytes.length);

                ServletOutputStream output = response.getOutputStream();
                output.write(chunk1);
                output.flush();

                sleep(500);

                output.write(chunk2);
                output.flush();
            }
        });

        ContentResponse response = client.newRequest("localhost", connector.getLocalPort())
                .scheme(scheme)
                .send();

        Assert.assertEquals(200, response.getStatus());
        Assert.assertArrayEquals(data, response.getContent());
    }

    @Test
    public void testGZIPContentCorrupted() throws Exception
    {
        start(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                baseRequest.setHandled(true);
                response.setHeader("Content-Encoding", "gzip");
                // Not gzipped, will cause the client to blow up.
                response.getOutputStream().print("0123456789");
            }
        });

        final CountDownLatch latch = new CountDownLatch(1);
        client.newRequest("localhost", connector.getLocalPort())
                .scheme(scheme)
                .send(result ->
                {
                    if (result.isFailed())
                        latch.countDown();
                });

        Assert.assertTrue(latch.await(5, TimeUnit.SECONDS));
    }

    private static void sleep(long ms) throws IOException
    {
        try
        {
            TimeUnit.MILLISECONDS.sleep(ms);
        }
        catch (InterruptedException x)
        {
            throw new InterruptedIOException();
        }
    }
}
