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

package org.eclipse.jetty.ee9.test.client.transport;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.client.AsyncRequestContent;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.core.Is.is;
import static org.junit.jupiter.api.Assertions.assertTrue;

// TODO: this test fails because the second read blocks in another thread,
//  but then the Servlet thread exits service(). Since the request has not
//  been started async, the implementation tries to complete, but finds
//  the blocked read as illegal (thrown from ChannelCallback.succeeded()).
//  Throwing IllegalStateException from ChannelCallback causes a series
//  of other IllegalStateException to be thrown.
//  Need to find a better failure path, so the blocked read throws a proper
//  IOException and the implementation is able to clean up properly.
@Disabled
public class BlockedIOTest extends AbstractTest
{
    @ParameterizedTest
    @MethodSource("transports")
    public void testBlockingReadThenNormalComplete(Transport transport) throws Exception
    {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch stopped = new CountDownLatch(1);
        AtomicReference<Throwable> readException = new AtomicReference<>();
        AtomicReference<Throwable> reReadException = new AtomicReference<>();

        start(transport, new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
            {
                new Thread(() ->
                {
                    try
                    {
                        int b = request.getInputStream().read();
                        if (b == '1')
                        {
                            started.countDown();
                            request.getInputStream().read();
                            // The read() above should block since the client does
                            // not send more data, and then throw when service() exits.
                            throw new IllegalStateException();
                        }
                    }
                    catch (Throwable ex1)
                    {
                        readException.set(ex1);
                        try
                        {
                            request.getInputStream().read();
                            // The read() above should throw immediately.
                            throw new IllegalStateException();
                        }
                        catch (Throwable ex2)
                        {
                            reReadException.set(ex2);
                        }
                        finally
                        {
                            stopped.countDown();
                        }
                    }
                }).start();

                try
                {
                    // wait for thread to start and read first byte
                    assertTrue(started.await(10, TimeUnit.SECONDS));
                    // give it time to block on second byte
                    Thread.sleep(1000);
                }
                catch (Throwable e)
                {
                    throw new ServletException(e);
                }

                response.setStatus(200);
                response.setContentType("text/plain");
                response.getOutputStream().print("OK\r\n");
            }
        });

        AsyncRequestContent requestContent = new AsyncRequestContent();
        CountDownLatch ok = new CountDownLatch(2);
        client.newRequest(newURI(transport))
            .method("POST")
            .body(requestContent)
            .onResponseContent((response, content) ->
            {
                assertThat(BufferUtil.toString(content), containsString("OK"));
                ok.countDown();
            })
            .onResponseSuccess(response ->
            {
                try
                {
                    assertThat(response.getStatus(), is(200));
                    assertTrue(stopped.await(10, TimeUnit.SECONDS));
                    ok.countDown();
                }
                catch (Throwable t)
                {
                    t.printStackTrace();
                }
            })
            .send(null);
        requestContent.write(BufferUtil.toBuffer("1"), Callback.NOOP);

        assertTrue(ok.await(10, TimeUnit.SECONDS));
        assertThat(readException.get(), instanceOf(IOException.class));
        assertThat(reReadException.get(), instanceOf(IOException.class));
    }
}
