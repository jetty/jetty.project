//
//  ========================================================================
//  Copyright (c) 1995-2018 Mort Bay Consulting Pty. Ltd.
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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ArgumentsSource;

public class HttpClientGZIPTest extends AbstractHttpClientServerTest
{
    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void testGZIPContentEncoding(Scenario scenario) throws Exception
    {
        final byte[] data = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9};
        start(scenario, new AbstractHandler()
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
                .scheme(scenario.getScheme())
                .timeout(5, TimeUnit.SECONDS)
                .send();

        assertEquals(200, response.getStatus());
        assertArrayEquals(data, response.getContent());
    }

    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void testGZIPContentOneByteAtATime(Scenario scenario) throws Exception
    {
        final byte[] data = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9};
        start(scenario, new AbstractHandler()
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
                .scheme(scenario.getScheme())
                .send();

        assertEquals(200, response.getStatus());
        assertArrayEquals(data, response.getContent());
    }

    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void testGZIPContentSentTwiceInOneWrite(Scenario scenario) throws Exception
    {
        final byte[] data = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9};
        start(scenario, new AbstractHandler()
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
                .scheme(scenario.getScheme())
                .send();

        assertEquals(200, response.getStatus());

        byte[] expected = Arrays.copyOf(data, 2 * data.length);
        System.arraycopy(data, 0, expected, data.length, data.length);
        assertArrayEquals(expected, response.getContent());
    }

    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void testGZIPContentFragmentedBeforeTrailer(Scenario scenario) throws Exception
    {
        // There are 8 trailer bytes to gzip encoding.
        testGZIPContentFragmented(scenario, 9);
    }

    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void testGZIPContentFragmentedAtTrailer(Scenario scenario) throws Exception
    {
        // There are 8 trailer bytes to gzip encoding.
        testGZIPContentFragmented(scenario, 1);
    }

    private void testGZIPContentFragmented(Scenario scenario, final int fragment) throws Exception
    {
        final byte[] data = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9};
        start(scenario, new AbstractHandler()
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
                .scheme(scenario.getScheme())
                .send();

        assertEquals(200, response.getStatus());
        assertArrayEquals(data, response.getContent());
    }

    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void testGZIPContentCorrupted(Scenario scenario) throws Exception
    {
        start(scenario, new AbstractHandler()
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
                .scheme(scenario.getScheme())
                .send(result ->
                {
                    if (result.isFailed())
                        latch.countDown();
                });

        assertTrue(latch.await(5, TimeUnit.SECONDS));
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
