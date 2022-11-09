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
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.Result;
import org.eclipse.jetty.client.util.BufferingResponseListener;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http2.api.Session;
import org.eclipse.jetty.http2.api.Stream;
import org.eclipse.jetty.http2.api.server.ServerSessionListener;
import org.eclipse.jetty.http2.frames.HeadersFrame;
import org.eclipse.jetty.http2.frames.PushPromiseFrame;
import org.eclipse.jetty.http2.frames.ResetFrame;
import org.eclipse.jetty.http2.frames.SettingsFrame;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.Promise;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

        ContentResponse response = httpClient.newRequest("localhost", connector.getLocalPort())
            .onPush((mainRequest, pushedRequest) -> null)
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
        ContentResponse response = httpClient.newRequest("localhost", connector.getLocalPort())
            .onPush((mainRequest, pushedRequest) -> new BufferingResponseListener()
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
        ContentResponse response = httpClient.newRequest("localhost", connector.getLocalPort())
            .onPush((mainRequest, pushedRequest) -> new BufferingResponseListener()
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

    @Test
    public void testPushDisabled() throws Exception
    {
        String primaryResource = "/primary.html";
        String secondaryResource = "/secondary.png";
        String secondaryData = "SECONDARY";
        start(new Handler.Processor()
        {
            @Override
            public void process(Request request, Response response, Callback callback)
            {
                String requestURI = request.getPathInContext();
                if (requestURI.endsWith(primaryResource))
                {
                    assertFalse(request.getConnectionMetaData().isPushSupported());
                    Content.Sink.write(response, true, "<html><head></head><body>PRIMARY</body></html>", callback);
                }
                else if (requestURI.endsWith(secondaryResource))
                {
                    Content.Sink.write(response, true, secondaryData, callback);
                }
                else
                {
                    callback.succeeded();
                }
            }
        });

        Session session = newClientSession(new Session.Listener()
        {
            @Override
            public Map<Integer, Integer> onPreface(Session session)
            {
                Map<Integer, Integer> settings = new HashMap<>();
                settings.put(SettingsFrame.ENABLE_PUSH, 0);
                return settings;
            }
        });

        // Request for the primary and secondary resource to build the cache.
        HttpFields.Mutable primaryFields = HttpFields.build();
        MetaData.Request primaryRequest = newRequest("GET", primaryResource, primaryFields);
        String referrerURI = primaryRequest.getURIString();
        CountDownLatch warmupLatch = new CountDownLatch(1);
        session.newStream(new HeadersFrame(primaryRequest, null, true), new Stream.Listener()
        {
            @Override
            public void onDataAvailable(Stream stream)
            {
                Stream.Data data = stream.readData();
                if (data == null)
                {
                    stream.demand();
                    return;
                }
                data.release();
                if (data.frame().isEndStream())
                {
                    // Request for the secondary resource.
                    HttpFields.Mutable secondaryFields = HttpFields.build();
                    secondaryFields.put(HttpHeader.REFERER, referrerURI);
                    MetaData.Request secondaryRequest = newRequest("GET", secondaryResource, secondaryFields);
                    session.newStream(new HeadersFrame(secondaryRequest, null, true), new Stream.Listener()
                    {
                        @Override
                        public void onDataAvailable(Stream stream)
                        {
                            Stream.Data data = stream.readData();
                            if (data == null)
                            {
                                stream.demand();
                                return;
                            }
                            data.release();
                            if (data.frame().isEndStream())
                                warmupLatch.countDown();
                        }
                    });
                }
            }
        });
        assertTrue(warmupLatch.await(5, TimeUnit.SECONDS));

        // Request again the primary resource, we should not get the secondary resource pushed.
        primaryRequest = newRequest("GET", primaryResource, primaryFields);
        CountDownLatch primaryResponseLatch = new CountDownLatch(1);
        CountDownLatch pushLatch = new CountDownLatch(1);
        session.newStream(new HeadersFrame(primaryRequest, null, true), new Promise.Adapter<>(), new Stream.Listener()
        {
            @Override
            public Stream.Listener onPush(Stream stream, PushPromiseFrame frame)
            {
                pushLatch.countDown();
                return null;
            }

            @Override
            public void onDataAvailable(Stream stream)
            {
                Stream.Data data = stream.readData();
                if (data == null)
                {
                    stream.demand();
                    return;
                }
                data.release();
                if (data.frame().isEndStream())
                    primaryResponseLatch.countDown();
            }
        });
        assertTrue(primaryResponseLatch.await(5, TimeUnit.SECONDS));
        assertFalse(pushLatch.await(1, TimeUnit.SECONDS));
    }
}
