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

package org.eclipse.jetty.ee9.test.client.transport;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.PushBuilder;
import org.eclipse.jetty.client.BufferingResponseListener;
import org.eclipse.jetty.client.ContentResponse;
import org.eclipse.jetty.client.Result;
import org.eclipse.jetty.ee9.servlet.DefaultServlet;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.server.NetworkConnector;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class PushedResourcesTest extends AbstractTest
{
    @ParameterizedTest
    @MethodSource("transportsWithPushSupport")
    public void testPushedResources(Transport transport) throws Exception
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
        start(transport, new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                String target = request.getRequestURI();
                if (target.equals(path1))
                {
                    response.getOutputStream().write(pushBytes1);
                }
                else if (target.equals(path2))
                {
                    response.getOutputStream().write(pushBytes2);
                }
                else
                {
                    request.newPushBuilder()
                        .path(path1)
                        .push();
                    request.newPushBuilder()
                        .path(path2)
                        .push();
                    response.getOutputStream().write(bytes);
                }
            }
        });

        CountDownLatch latch1 = new CountDownLatch(1);
        CountDownLatch latch2 = new CountDownLatch(1);
        ContentResponse response = client.newRequest(newURI(transport))
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

    @ParameterizedTest
    @MethodSource("transportsWithPushSupport")
    public void testPushedResourcesSomewhatLikeTCK(Transport transport) throws Exception
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
        start(transport, new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                String target = request.getRequestURI();
                if (target.equals(path1))
                {
                    response.getOutputStream().write(pushBytes1);
                }
                else if (target.equals(path2))
                {
                    response.getOutputStream().write(pushBytes2);
                }
                else
                {
                    try
                    {
                        PushBuilder pb = request.newPushBuilder();
                        pb.push();
                    }
                    catch (Exception e)
                    {
                        System.err.println("Expected error empty push builder");
                    }

                    PushBuilder pb1 = request.newPushBuilder();
                    pb1.path(path1);
                    pb1.push();
                    PushBuilder pb2 = request.newPushBuilder();
                    pb2.path(path2);
                    pb2.push();

                    try
                    {
                        pb2.push();
                    }
                    catch (Exception e)
                    {
                        System.err.println("Expected error no path reset");
                    }

                    PushBuilder pb3 = request.newPushBuilder();
                    try
                    {
                        pb3.method(null);
                    }
                    catch (Exception e)
                    {
                        System.err.println("Expected error null method");
                    }

                    String[] methods = {
                        "", "POST", "PUT", "DELETE",
                        "CONNECT", "OPTIONS", "TRACE"
                    };

                    for (String m : methods)
                    {
                        try
                        {
                            pb3.method(m);
                            System.err.println("Fail " + m);
                        }
                        catch (Exception e)
                        {
                            System.err.println("Pass " + m);
                        }
                    }
                    response.getOutputStream().write(bytes);
                }
            }
        });

        CountDownLatch latch1 = new CountDownLatch(1);
        CountDownLatch latch2 = new CountDownLatch(1);
        ContentResponse response = client.newRequest(newURI(transport))
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

    @ParameterizedTest
    @MethodSource("transportsWithPushSupport")
    public void testPushedResourcesLikeTCK(Transport transport) throws Exception
    {
        String path1 = "/secondary1.html";

        prepareServer(transport, new DefaultServlet());
        Path staticDir = MavenTestingUtils.getTestResourcePath("serverpushtck");
        assertNotNull(staticDir);
        servletContextHandler.setBaseResourceAsPath(staticDir);
        addServlet(
            new HttpServlet()
            {
                @Override
                protected void service(HttpServletRequest request, HttpServletResponse response) throws IOException
                {
                    try
                    {
                        PushBuilder pb = request.newPushBuilder();
                        pb.push();
                    }
                    catch (Exception e)
                    {
                        System.err.println("Expected error empty push builder");
                    }

                    PushBuilder pb1 = request.newPushBuilder();
                    pb1.path(path1);
                    pb1.push();

                    try
                    {
                        pb1.push();
                    }
                    catch (Exception e)
                    {
                        System.err.println("Expected error no path reset");
                    }

                    PushBuilder pb3 = request.newPushBuilder();
                    try
                    {
                        pb3.method(null);
                    }
                    catch (Exception e)
                    {
                        System.err.println("Expected error null method");
                    }

                    String[] methods = {
                        "", "POST", "PUT", "DELETE",
                        "CONNECT", "OPTIONS", "TRACE"
                    };

                    for (String m : methods)
                    {
                        try
                        {
                            pb3.method(m);
                            System.err.println("Fail " + m);
                        }
                        catch (Exception e)
                        {
                            System.err.println("Pass " + m);
                        }
                    }
                    response.getWriter().println("TEST FINISHED");
                }
            },
            "/serverpushtck/*");

        server.start();
        startClient(transport);
        CountDownLatch latch1 = new CountDownLatch(1);

        String scheme = transport.isSecure() ? "https" : "http";
        String uri = scheme + "://localhost";
        if (connector instanceof NetworkConnector networkConnector)
            uri += ":" + networkConnector.getLocalPort();
        URI theURI = URI.create(uri + "/serverpushtck/foo");

        ContentResponse response = client.newRequest(theURI)
            .onPush((mainRequest, pushedRequest) -> new BufferingResponseListener()
            {
                @Override
                public void onComplete(Result result)
                {
                    assertTrue(result.isSucceeded());
                    if (pushedRequest.getPath().equals(path1))
                    {
                        assertTrue(getContentAsString().contains("SECONDARY 1"));
                        latch1.countDown();
                    }
                }
            })
            .timeout(5, TimeUnit.SECONDS)
            .send();

        assertEquals(HttpStatus.OK_200, response.getStatus());
        assertTrue(response.getContentAsString().contains("TEST FINISHED"));
        assertTrue(latch1.await(5, TimeUnit.SECONDS));
    }

    @ParameterizedTest
    @MethodSource("transportsWithPushSupport")
    public void testPushedResourceRedirect(Transport transport) throws Exception
    {
        Random random = new Random();
        byte[] pushBytes = new byte[512];
        random.nextBytes(pushBytes);

        String oldPath = "/old";
        String newPath = "/new";
        start(transport, new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                String target = request.getRequestURI();
                if (target.equals(oldPath))
                    response.sendRedirect(newPath);
                else if (target.equals(newPath))
                    response.getOutputStream().write(pushBytes);
                else
                    request.newPushBuilder().path(oldPath).push();
            }
        });

        CountDownLatch latch = new CountDownLatch(1);

        ContentResponse response = client.newRequest(newURI(transport))
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
}
