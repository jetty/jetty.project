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

package org.eclipse.jetty.client;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.io.ArrayByteBufferPool;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.IO;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ArgumentsSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.lessThan;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

public class HttpClientGZIPTest extends AbstractHttpClientServerTest
{
    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void testGZIPContentEncoding(Scenario scenario) throws Exception
    {
        final byte[] data = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9};
        start(scenario, new EmptyServerHandler()
        {
            @Override
            protected void service(Request request, org.eclipse.jetty.server.Response response) throws Exception
            {
                response.getHeaders().put(HttpHeader.CONTENT_ENCODING, "gzip");

                GZIPOutputStream gzipOutput = new GZIPOutputStream(Content.Sink.asOutputStream(response));
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
        HttpFields responseHeaders = response.getHeaders();
        // The content has been decoded, so Content-Encoding must be absent.
        assertNull(responseHeaders.get(HttpHeader.CONTENT_ENCODING));
        // The Content-Length must be the decoded one.
        assertEquals(data.length, responseHeaders.getLongField(HttpHeader.CONTENT_LENGTH));
    }

    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void testMultipleContentEncodingsFooGZIP(Scenario scenario) throws Exception
    {
        final byte[] data = "HELLO WORLD".getBytes(StandardCharsets.UTF_8);
        start(scenario, new EmptyServerHandler()
        {
            @Override
            protected void service(Request request, org.eclipse.jetty.server.Response response) throws Exception
            {
                response.getHeaders().put(HttpHeader.CONTENT_ENCODING, "foo,gzip");

                GZIPOutputStream gzipOutput = new GZIPOutputStream(Content.Sink.asOutputStream(response));
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
        HttpFields responseHeaders = response.getHeaders();
        // The content has been decoded, so Content-Encoding must be only be "foo".
        assertEquals("foo", responseHeaders.get(HttpHeader.CONTENT_ENCODING));
        // The Content-Length must be the decoded one.
        assertEquals(data.length, responseHeaders.getLongField(HttpHeader.CONTENT_LENGTH));
    }

    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void testMultipleContentEncodingsGZIPFoo(Scenario scenario) throws Exception
    {
        final byte[] data = "HELLO WORLD".getBytes(StandardCharsets.UTF_8);
        start(scenario, new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, org.eclipse.jetty.server.Response response, Callback callback) throws Exception
            {
                ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                GZIPOutputStream gzipOutput = new GZIPOutputStream(buffer);
                gzipOutput.write(data);
                gzipOutput.finish();

                byte[] bytes = buffer.toByteArray();
                response.getHeaders().put(HttpHeader.CONTENT_LENGTH, bytes.length);
                response.getHeaders().put(HttpHeader.CONTENT_ENCODING, "gzip,foo");
                response.write(true, ByteBuffer.wrap(bytes), callback);
                return true;
            }
        });

        // There is no "foo" content decoder factory, so content must remain gzipped.
        AtomicLong encodedContentLength = new AtomicLong();
        ContentResponse response = client.newRequest("localhost", connector.getLocalPort())
            .scheme(scenario.getScheme())
            .onResponseHeader((r, field) ->
            {
                if (field.getHeader() == HttpHeader.CONTENT_LENGTH)
                    encodedContentLength.set(field.getLongValue());
                return true;
            })
            .timeout(5, TimeUnit.SECONDS)
            .send();

        assertEquals(200, response.getStatus());

        byte[] content = IO.readBytes(new GZIPInputStream(new ByteArrayInputStream(response.getContent())));
        assertArrayEquals(data, content);
        HttpFields responseHeaders = response.getHeaders();
        assertEquals("gzip,foo", responseHeaders.get(HttpHeader.CONTENT_ENCODING));
        assertEquals(encodedContentLength.get(), responseHeaders.getLongField(HttpHeader.CONTENT_LENGTH));
    }

    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void testGZIPContentOneByteAtATime(Scenario scenario) throws Exception
    {
        final byte[] data = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9};
        start(scenario, new EmptyServerHandler()
        {
            @Override
            protected void service(Request request, org.eclipse.jetty.server.Response response) throws Exception
            {
                response.getHeaders().put("Content-Encoding", "gzip");

                ByteArrayOutputStream gzipData = new ByteArrayOutputStream();
                GZIPOutputStream gzipOutput = new GZIPOutputStream(gzipData);
                gzipOutput.write(data);
                gzipOutput.finish();

                byte[] gzipBytes = gzipData.toByteArray();
                for (byte gzipByte : gzipBytes)
                {
                    Content.Sink.write(response, false, ByteBuffer.wrap(new byte[]{gzipByte}));
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
        start(scenario, new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, org.eclipse.jetty.server.Response response, Callback callback) throws Exception
            {
                response.getHeaders().put("Content-Encoding", "gzip");

                ByteArrayOutputStream gzipData = new ByteArrayOutputStream();
                GZIPOutputStream gzipOutput = new GZIPOutputStream(gzipData);
                gzipOutput.write(data);
                gzipOutput.finish();

                byte[] gzipBytes = gzipData.toByteArray();
                byte[] content = Arrays.copyOf(gzipBytes, 2 * gzipBytes.length);
                System.arraycopy(gzipBytes, 0, content, gzipBytes.length, gzipBytes.length);

                response.write(true, ByteBuffer.wrap(content), callback);
                return true;
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
        start(scenario, new EmptyServerHandler()
        {
            @Override
            protected void service(Request request, org.eclipse.jetty.server.Response response) throws Exception
            {
                response.getHeaders().put("Content-Encoding", "gzip");

                ByteArrayOutputStream gzipData = new ByteArrayOutputStream();
                GZIPOutputStream gzipOutput = new GZIPOutputStream(gzipData);
                gzipOutput.write(data);
                gzipOutput.finish();

                byte[] gzipBytes = gzipData.toByteArray();
                byte[] chunk1 = Arrays.copyOfRange(gzipBytes, 0, gzipBytes.length - fragment);
                byte[] chunk2 = Arrays.copyOfRange(gzipBytes, gzipBytes.length - fragment, gzipBytes.length);

                Content.Sink.write(response, false, ByteBuffer.wrap(chunk1));

                sleep(500);

                Content.Sink.write(response, true, ByteBuffer.wrap(chunk2));
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
        start(scenario, new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, org.eclipse.jetty.server.Response response, Callback callback)
            {
                response.getHeaders().put("Content-Encoding", "gzip");
                // Not gzipped, will cause the client to blow up.
                Content.Sink.write(response, true, "0123456789", callback);
                return true;
            }
        });

        assertThrows(ExecutionException.class, () ->
            client.newRequest("localhost", connector.getLocalPort())
                .scheme(scenario.getScheme())
                .timeout(5, TimeUnit.SECONDS)
                .send());
    }

    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void testLargeGZIPContentDoesNotPolluteByteBufferPool(Scenario scenario) throws Exception
    {
        String digits = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
        Random random = new Random();
        byte[] content = new byte[1024 * 1024];
        for (int i = 0; i < content.length; ++i)
        {
            content[i] = (byte)digits.charAt(random.nextInt(digits.length()));
        }
        start(scenario, new EmptyServerHandler()
        {
            @Override
            protected void service(Request request, org.eclipse.jetty.server.Response response) throws Exception
            {
                response.getHeaders().put(HttpHeader.CONTENT_TYPE, "text/plain;charset=" + StandardCharsets.US_ASCII.name());
                response.getHeaders().put(HttpHeader.CONTENT_ENCODING, "gzip");
                GZIPOutputStream gzip = new GZIPOutputStream(Content.Sink.asOutputStream(response));
                gzip.write(content);
                gzip.finish();
            }
        });

        ByteBufferPool pool = client.getByteBufferPool();
        assumeTrue(pool instanceof ArrayByteBufferPool);
        ArrayByteBufferPool bufferPool = (ArrayByteBufferPool)pool;

        ContentResponse response = client.newRequest("localhost", connector.getLocalPort())
            .scheme(scenario.getScheme())
            .timeout(5, TimeUnit.SECONDS)
            .send();

        assertEquals(HttpStatus.OK_200, response.getStatus());
        assertArrayEquals(content, response.getContent());

        long directMemory = bufferPool.getDirectMemory();
        assertThat(directMemory, lessThan((long)content.length));
        long heapMemory = bufferPool.getHeapMemory();
        assertThat(heapMemory, lessThan((long)content.length));
    }

    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void testLargeGZIPContentAsync(Scenario scenario) throws Exception
    {
        String digits = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
        Random random = new Random();
        byte[] content = new byte[32 * 1024 * 1024];
        for (int i = 0; i < content.length; ++i)
        {
            content[i] = (byte)digits.charAt(random.nextInt(digits.length()));
        }
        start(scenario, new EmptyServerHandler()
        {
            @Override
            protected void service(Request request, org.eclipse.jetty.server.Response response) throws Exception
            {
                response.getHeaders().put(HttpHeader.CONTENT_TYPE, "text/plain;charset=" + StandardCharsets.US_ASCII.name());
                response.getHeaders().put(HttpHeader.CONTENT_ENCODING, "gzip");
                GZIPOutputStream gzip = new GZIPOutputStream(Content.Sink.asOutputStream(response));
                gzip.write(content);
                gzip.finish();
            }
        });

        try (InputStreamResponseListener listener = new InputStreamResponseListener())
        {
            client.newRequest("localhost", connector.getLocalPort())
                .scheme(scenario.getScheme())
                .timeout(20, TimeUnit.SECONDS)
                .send(listener);

            Response response = listener.get(20, TimeUnit.SECONDS);
            assertEquals(HttpStatus.OK_200, response.getStatus());
            // No Content-Length because HttpClient does not know yet the length of the decoded content.
            assertNull(response.getHeaders().get(HttpHeader.CONTENT_LENGTH));
            // No Content-Encoding, because the content will be decoded automatically.
            // In this way applications will know that the content is already un-gzipped
            // and will not do the un-gzipping themselves.
            assertNull(response.getHeaders().get(HttpHeader.CONTENT_ENCODING));

            ByteArrayOutputStream output = new ByteArrayOutputStream();
            try (InputStream input = listener.getInputStream())
            {
                IO.copy(input, output);
            }
            assertArrayEquals(content, output.toByteArray());
            // After the content has been decoded, the length is known again.
            assertEquals(content.length, response.getHeaders().getLongField(HttpHeader.CONTENT_LENGTH));
        }
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
