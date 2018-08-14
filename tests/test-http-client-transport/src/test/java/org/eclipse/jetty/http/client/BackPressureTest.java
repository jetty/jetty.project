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

package org.eclipse.jetty.http.client;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPOutputStream;

import javax.servlet.ServletException;
import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.util.BackPressure;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.StringUtil;
import org.hamcrest.Matchers;
import org.junit.Assert;
import org.junit.Assume;
import org.junit.Test;

public class BackPressureTest extends AbstractTest
{
    public BackPressureTest(Transport transport)
    {
        super(transport);
    }

    @Test
    public void testClearSingleContentListenerBackPressure() throws Exception
    {
        Assume.assumeThat(transport, Matchers.not(Transport.FCGI));
        testSingleContentListenerBackPressure(false);
    }

    @Test
    public void testGZipSingleContentListenerBackPressure() throws Exception
    {
        testSingleContentListenerBackPressure(true);
    }

    private void testSingleContentListenerBackPressure(boolean gzip) throws Exception
    {
        String data1 = "hello";
        byte[] chunk1 = StringUtil.getUtf8Bytes(data1);
        String data2 = "world";
        byte[] chunk2 = StringUtil.getUtf8Bytes(data2);
        String data = data1 + data2;
        byte[] bytes = StringUtil.getUtf8Bytes(data);

        start(new EmptyServerHandler()
        {
            @Override
            protected void service(String target, Request jettyRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                response.setStatus(HttpStatus.OK_200);
                if (gzip)
                    response.setHeader("Content-Encoding", "gzip");
                response.flushBuffer();

                ServletOutputStream stream = response.getOutputStream();
                OutputStream output = gzip ? new GZIPOutputStream(stream, true) : stream;

                output.write(chunk1);
                output.flush();

                output.write(chunk2);
                output.flush();
            }
        });

        BlockingQueue<byte[]> buffers = new LinkedBlockingDeque<>();
        BlockingQueue<Callback> callbacks = new LinkedBlockingDeque<>();
        CountDownLatch resultLatch = new CountDownLatch(1);
        client.newRequest(newURI())
                .onResponseContentAsync((response, content, callback) ->
                {
                    // Must copy because we call release()/succeeded() below.
                    buffers.offer(BufferUtil.toArray(content));
                    callbacks.offer(callback);
                })
                .send(result ->
                {
                    if (result.isSucceeded())
                    {
                        if (result.getResponse().getStatus() == HttpStatus.OK_200)
                            resultLatch.countDown();
                    }
                });

        Callback callback = callbacks.poll(1, TimeUnit.SECONDS);
        Assert.assertThat(callback, Matchers.instanceOf(BackPressure.class));

        BackPressure backPressure = (BackPressure)callback;
        // Release but don't demand, we must not get another chunk of content.
        backPressure.release();
        callback = callbacks.poll(1, TimeUnit.SECONDS);
        Assert.assertNull(callback);

        // Demand, we should get another chunk of content.
        backPressure.demand();
        callback = callbacks.poll(1, TimeUnit.SECONDS);
        Assert.assertThat(callback, Matchers.instanceOf(BackPressure.class));

        // If we don't demand/succeed, we should not get the result yet.
        Assert.assertFalse(resultLatch.await(1, TimeUnit.SECONDS));
        // Succeed, we should get the result.
        callback.succeeded();
        Assert.assertTrue(resultLatch.await(1, TimeUnit.SECONDS));

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        for (byte[] buffer : buffers)
            baos.write(buffer);
        Assert.assertArrayEquals(bytes, baos.toByteArray());
    }

    @Test
    public void testClearManyContentListenerBackPressure() throws Exception
    {
        testManyContentListenerBackPressure(false);
    }

    @Test
    public void testGZipManyContentListenerBackPressure() throws Exception
    {
        testManyContentListenerBackPressure(true);
    }

    private void testManyContentListenerBackPressure(boolean gzip) throws Exception
    {
        String data1 = "hello";
        byte[] chunk1 = StringUtil.getUtf8Bytes(data1);
        String data2 = "world";
        byte[] chunk2 = StringUtil.getUtf8Bytes(data2);
        String data = data1 + data2;
        byte[] bytes = StringUtil.getUtf8Bytes(data);

        start(new EmptyServerHandler()
        {
            @Override
            protected void service(String target, Request jettyRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                response.setStatus(HttpStatus.OK_200);
                if (gzip)
                    response.setHeader("Content-Encoding", "gzip");
                response.flushBuffer();

                ServletOutputStream stream = response.getOutputStream();
                OutputStream output = gzip ? new GZIPOutputStream(stream, true) : stream;

                output.write(chunk1);
                output.flush();

                output.write(chunk2);
                output.flush();
            }
        });

        BlockingQueue<byte[]> buffers1 = new LinkedBlockingDeque<>();
        BlockingQueue<Callback> callbacks1 = new LinkedBlockingDeque<>();
        BlockingQueue<byte[]> buffers2 = new LinkedBlockingDeque<>();
        BlockingQueue<Callback> callbacks2 = new LinkedBlockingDeque<>();
        CountDownLatch resultLatch = new CountDownLatch(1);
        client.newRequest(newURI())
                .onResponseContentAsync((response, content, callback) ->
                {
                    // Must copy because we call release()/succeeded() below.
                    buffers1.offer(BufferUtil.toArray(content));
                    callbacks1.offer(callback);
                })
                .onResponseContentAsync((response, content, callback) ->
                {
                    // Must copy because we call release()/succeeded() below.
                    buffers2.offer(BufferUtil.toArray(content));
                    callbacks2.offer(callback);
                })
                .send(result ->
                {
                    if (result.isSucceeded())
                    {
                        if (result.getResponse().getStatus() == HttpStatus.OK_200)
                            resultLatch.countDown();
                    }
                });

        Callback callback1 = callbacks1.poll(1, TimeUnit.SECONDS);
        Assert.assertThat(callback1, Matchers.instanceOf(BackPressure.class));
        // Both listeners are invoked immediately.
        Callback callback2 = callbacks2.poll(1, TimeUnit.SECONDS);
        Assert.assertThat(callback2, Matchers.instanceOf(BackPressure.class));

        BackPressure backPressure1 = (BackPressure)callback1;
        // Demand with one callback, we must not get another chunk of content.
        backPressure1.demand();
        callback1 = callbacks1.poll(1, TimeUnit.SECONDS);
        Assert.assertNull(callback1);

        // Succeed with the other callback, we must get another chunk of content.
        callback2.succeeded();

        callback1 = callbacks1.poll(1, TimeUnit.SECONDS);
        Assert.assertThat(callback1, Matchers.instanceOf(BackPressure.class));
        callback2 = callbacks2.poll(1, TimeUnit.SECONDS);
        Assert.assertThat(callback2, Matchers.instanceOf(BackPressure.class));

        // Succeed with one callback, we must not get another chunk of content.
        callback1.succeeded();
        callback1 = callbacks1.poll(1, TimeUnit.SECONDS);
        Assert.assertNull(callback1);

        // Demand with the other callback, we must get the result.
        BackPressure backPressure2 = (BackPressure)callback2;
        backPressure2.release();
        backPressure2.demand();
        Assert.assertTrue(resultLatch.await(1, TimeUnit.SECONDS));

        ByteArrayOutputStream baos1 = new ByteArrayOutputStream();
        for (byte[] buffer : buffers1)
            baos1.write(buffer);
        Assert.assertArrayEquals(bytes, baos1.toByteArray());

        ByteArrayOutputStream baos2 = new ByteArrayOutputStream();
        for (byte[] buffer : buffers2)
            baos2.write(buffer);
        Assert.assertArrayEquals(bytes, baos2.toByteArray());
    }
}
