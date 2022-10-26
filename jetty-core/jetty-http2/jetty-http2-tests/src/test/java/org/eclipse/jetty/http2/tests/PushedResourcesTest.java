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

package org.eclipse.jetty.http2.tests;

import java.nio.ByteBuffer;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.client.HttpRequest;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.Result;
import org.eclipse.jetty.client.util.BufferingResponseListener;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http2.api.Stream;
import org.eclipse.jetty.http2.api.server.ServerSessionListener;
import org.eclipse.jetty.http2.frames.HeadersFrame;
import org.eclipse.jetty.http2.frames.PushPromiseFrame;
import org.eclipse.jetty.http2.frames.ResetFrame;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.Promise;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class PushedResourcesTest extends AbstractTest
{
    @Test
    public void testPushedResourceCancelled() throws Exception
    {
        String pushPath = "/secondary";
        CountDownLatch latch = new CountDownLatch(1);
        start(new ServerSessionListener()
        {
            @Override
            public Stream.Listener onNewStream(Stream stream, HeadersFrame frame)
            {
                HttpURI pushURI = HttpURI.from("http://localhost:" + connector.getLocalPort() + pushPath);
                MetaData.Request pushRequest = new MetaData.Request(HttpMethod.GET.asString(), pushURI, HttpVersion.HTTP_2, HttpFields.EMPTY);
                stream.push(new PushPromiseFrame(stream.getId(), pushRequest), new Promise.Adapter<>()
                {
                    @Override
                    public void succeeded(Stream pushStream)
                    {
                        // Just send the normal response and wait for the reset.
                        MetaData.Response response = new MetaData.Response(HttpVersion.HTTP_2, HttpStatus.OK_200, HttpFields.EMPTY);
                        stream.headers(new HeadersFrame(stream.getId(), response, null, true), Callback.NOOP);
                    }
                }, new Stream.Listener()
                {
                    @Override
                    public void onReset(Stream stream, ResetFrame frame, Callback callback)
                    {
                        latch.countDown();
                        callback.succeeded();
                    }
                });
                return null;
            }
        });

        HttpRequest request = (HttpRequest)httpClient.newRequest("localhost", connector.getLocalPort());
        ContentResponse response = request
            .pushListener((mainRequest, pushedRequest) -> null)
            .timeout(5, TimeUnit.SECONDS)
            .send();

        assertEquals(HttpStatus.OK_200, response.getStatus());
        assertTrue(latch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testPushedResources() throws Exception
    {
        Random random = new Random();
        byte[] bytes = new byte[512];
        random.nextBytes(bytes);
        byte[] pushBytes1 = new byte[1024];
        random.nextBytes(pushBytes1);
        byte[] pushBytes2 = new byte[2048];
        random.nextBytes(pushBytes2);

        String path1 = "/secondary1";
        String path2 = "/secondary2";
        start(new Handler.Processor()
        {
            @Override
            public void process(Request request, Response response, Callback callback)
            {
                String target = Request.getPathInContext(request);
                if (target.equals(path1))
                {
                    response.write(true, ByteBuffer.wrap(pushBytes1), callback);
                }
                else if (target.equals(path2))
                {
                    response.write(true, ByteBuffer.wrap(pushBytes2), callback);
                }
                else
                {
                    MetaData.Request push1 = new MetaData.Request(null, HttpURI.build(request.getHttpURI()).path(path1), HttpVersion.HTTP_2, HttpFields.EMPTY);
                    request.push(push1);
                    MetaData.Request push2 = new MetaData.Request(null, HttpURI.build(request.getHttpURI()).path(path2), HttpVersion.HTTP_2, HttpFields.EMPTY);
                    request.push(push2);
                    response.write(true, ByteBuffer.wrap(bytes), callback);
                }
            }
        });

        CountDownLatch latch1 = new CountDownLatch(1);
        CountDownLatch latch2 = new CountDownLatch(1);
        HttpRequest request = (HttpRequest)httpClient.newRequest("localhost", connector.getLocalPort());
        ContentResponse response = request
            .pushListener((mainRequest, pushedRequest) -> new BufferingResponseListener()
            {
                @Override
                public void onComplete(Result result)
                {
                    assertTrue(result.isSucceeded());
                    if (pushedRequest.getPath().equals(path1))
                    {
                        assertArrayEquals(pushBytes1, getContent());
                        latch1.countDown();
                    }
                    else if (pushedRequest.getPath().equals(path2))
                    {
                        assertArrayEquals(pushBytes2, getContent());
                        latch2.countDown();
                    }
                }
            })
            .timeout(5, TimeUnit.SECONDS)
            .send();

        assertEquals(HttpStatus.OK_200, response.getStatus());
        assertArrayEquals(bytes, response.getContent());
        assertTrue(latch1.await(5, TimeUnit.SECONDS));
        assertTrue(latch2.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testPushedResourceRedirect() throws Exception
    {
        Random random = new Random();
        byte[] pushBytes = new byte[512];
        random.nextBytes(pushBytes);

        String oldPath = "/old";
        String newPath = "/new";
        start(new Handler.Processor()
        {
            @Override
            public void process(Request request, Response response, Callback callback)
            {
                String target = Request.getPathInContext(request);
                if (target.equals(oldPath))
                {
                    Response.sendRedirect(request, response, callback, newPath);
                }
                else if (target.equals(newPath))
                {
                    response.write(true, ByteBuffer.wrap(pushBytes), callback);
                }
                else
                {
                    request.push(new MetaData.Request(null, HttpURI.build(request.getHttpURI()).path(oldPath), HttpVersion.HTTP_2, HttpFields.EMPTY));
                    callback.succeeded();
                }
            }
        });

        CountDownLatch latch = new CountDownLatch(1);
        HttpRequest request = (HttpRequest)httpClient.newRequest("localhost", connector.getLocalPort());
        ContentResponse response = request
            .pushListener((mainRequest, pushedRequest) -> new BufferingResponseListener()
            {
                @Override
                public void onComplete(Result result)
                {
                    assertTrue(result.isSucceeded());
                    assertEquals(oldPath, pushedRequest.getPath());
                    assertEquals(newPath, result.getRequest().getPath());
                    assertArrayEquals(pushBytes, getContent());
                    latch.countDown();
                }
            })
            .timeout(5, TimeUnit.SECONDS)
            .send();

        assertEquals(HttpStatus.OK_200, response.getStatus());
        assertTrue(latch.await(5, TimeUnit.SECONDS));
    }
}
