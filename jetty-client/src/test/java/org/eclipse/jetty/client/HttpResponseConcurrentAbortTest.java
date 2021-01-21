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

package org.eclipse.jetty.client;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.client.api.Response;
import org.eclipse.jetty.client.api.Result;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ArgumentsSource;

import static org.junit.jupiter.api.Assertions.assertTrue;

public class HttpResponseConcurrentAbortTest extends AbstractHttpClientServerTest
{
    private final CountDownLatch callbackLatch = new CountDownLatch(1);
    private final CountDownLatch failureLatch = new CountDownLatch(1);
    private final CountDownLatch completeLatch = new CountDownLatch(1);
    private final AtomicBoolean failureWasAsync = new AtomicBoolean();
    private final AtomicBoolean completeWasSync = new AtomicBoolean();

    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void testAbortOnBegin(Scenario scenario) throws Exception
    {
        start(scenario, new EmptyServerHandler());

        client.newRequest("localhost", connector.getLocalPort())
            .scheme(scenario.getScheme())
            .onResponseBegin(new Response.BeginListener()
            {
                @Override
                public void onBegin(Response response)
                {
                    abort(response);
                }
            })
            .send(new TestResponseListener());
        assertTrue(callbackLatch.await(5, TimeUnit.SECONDS));
        assertTrue(completeLatch.await(5, TimeUnit.SECONDS));
        assertTrue(failureWasAsync.get());
        assertTrue(completeWasSync.get());
    }

    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void testAbortOnHeader(Scenario scenario) throws Exception
    {
        start(scenario, new EmptyServerHandler());

        client.newRequest("localhost", connector.getLocalPort())
            .scheme(scenario.getScheme())
            .onResponseHeader(new Response.HeaderListener()
            {
                @Override
                public boolean onHeader(Response response, HttpField field)
                {
                    abort(response);
                    return true;
                }
            })
            .send(new TestResponseListener());
        assertTrue(callbackLatch.await(5, TimeUnit.SECONDS));
        assertTrue(completeLatch.await(5, TimeUnit.SECONDS));
        assertTrue(failureWasAsync.get());
        assertTrue(completeWasSync.get());
    }

    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void testAbortOnHeaders(Scenario scenario) throws Exception
    {
        start(scenario, new EmptyServerHandler());

        client.newRequest("localhost", connector.getLocalPort())
            .scheme(scenario.getScheme())
            .onResponseHeaders(new Response.HeadersListener()
            {
                @Override
                public void onHeaders(Response response)
                {
                    abort(response);
                }
            })
            .send(new TestResponseListener());
        assertTrue(callbackLatch.await(5, TimeUnit.SECONDS));
        assertTrue(completeLatch.await(5, TimeUnit.SECONDS));
        assertTrue(failureWasAsync.get());
        assertTrue(completeWasSync.get());
    }

    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void testAbortOnContent(Scenario scenario) throws Exception
    {
        start(scenario, new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                baseRequest.setHandled(true);
                OutputStream output = response.getOutputStream();
                output.write(1);
                output.flush();
            }
        });

        client.newRequest("localhost", connector.getLocalPort())
            .scheme(scenario.getScheme())
            .onResponseContent(new Response.ContentListener()
            {
                @Override
                public void onContent(Response response, ByteBuffer content)
                {
                    abort(response);
                }
            })
            .send(new TestResponseListener());
        assertTrue(callbackLatch.await(5, TimeUnit.SECONDS));
        assertTrue(completeLatch.await(5, TimeUnit.SECONDS));
        assertTrue(failureWasAsync.get());
        assertTrue(completeWasSync.get());
    }

    private void abort(final Response response)
    {
        new Thread("abort")
        {
            @Override
            public void run()
            {
                response.abort(new Exception());
            }
        }.start();

        try
        {
            // The failure callback is executed asynchronously, but
            // here we are within the context of another response
            // callback, which should detect that a failure happened
            // and therefore this thread should complete the response.
            failureWasAsync.set(failureLatch.await(2, TimeUnit.SECONDS));

            // The complete callback must be executed by this thread,
            // after we return from this response callback.
            completeWasSync.set(!completeLatch.await(1, TimeUnit.SECONDS));

            callbackLatch.countDown();
        }
        catch (InterruptedException x)
        {
            throw new RuntimeException(x);
        }
    }

    private class TestResponseListener extends Response.Listener.Adapter
    {
        @Override
        public void onFailure(Response response, Throwable failure)
        {
            failureLatch.countDown();
        }

        @Override
        public void onComplete(Result result)
        {
            assertTrue(result.isFailed());
            completeLatch.countDown();
        }
    }
}
