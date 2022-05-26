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

package org.eclipse.jetty.http.client;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.client.ContinueProtocolHandler;
import org.eclipse.jetty.client.HttpConversation;
import org.eclipse.jetty.client.HttpExchange;
import org.eclipse.jetty.client.HttpRequest;
import org.eclipse.jetty.client.ProtocolHandler;
import org.eclipse.jetty.client.api.Response;
import org.eclipse.jetty.client.api.Result;
import org.eclipse.jetty.client.util.AsyncRequestContent;
import org.eclipse.jetty.client.util.BufferingResponseListener;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpHeaderValue;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.IO;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ArgumentsSource;

import static org.eclipse.jetty.http.client.Transport.FCGI;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class IntermediateResponseTest extends AbstractTest<TransportScenario>
{
    @Override
    public void init(Transport transport) throws IOException
    {
        // Skip FCGI for now, not much interested in its server-side behavior.
        Assumptions.assumeTrue(transport != FCGI);
        setScenario(new TransportScenario(transport));
    }

    @ParameterizedTest
    @ArgumentsSource(TransportProvider.class)
    public void test100Continue(Transport transport) throws Exception
    {
        init(transport);
        scenario.start(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request jettyRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                jettyRequest.setHandled(true);
                String body = IO.toString(request.getInputStream());
                response.getOutputStream().print("read " + body.length());
            }
        });
        long idleTimeout = 10000;
        scenario.setRequestIdleTimeout(idleTimeout);

        AsyncRequestContent content = new AsyncRequestContent()
        {
            @Override
            public void demand()
            {
                super.demand();
            }
        };

        scenario.client.getProtocolHandlers().put(new ContinueProtocolHandler()
        {
            @Override
            protected void onContinue(org.eclipse.jetty.client.api.Request request)
            {
                super.onContinue(request);
                content.offer(BufferUtil.toBuffer("Some content!"), Callback.from(content::close));
            }
        });

        CountDownLatch complete = new CountDownLatch(1);
        AtomicReference<Response> response = new AtomicReference<>();
        BufferingResponseListener listener = new BufferingResponseListener()
        {
            @Override
            public void onComplete(Result result)
            {
                response.set(result.getResponse());
                complete.countDown();
            }
        };
        scenario.client.POST(scenario.newURI())
            .headers(headers -> headers.put(HttpHeader.EXPECT, HttpHeaderValue.CONTINUE))
            .body(content)
            .timeout(10, TimeUnit.SECONDS)
            .send(listener);

        assertTrue(complete.await(10, TimeUnit.SECONDS));
        assertThat(response.get().getStatus(), is(200));
        assertThat(listener.getContentAsString(), is("read 13"));
    }

    @ParameterizedTest
    @ArgumentsSource(TransportProvider.class)
    public void test102Processing(Transport transport) throws Exception
    {
        init(transport);
        scenario.start(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request jettyRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                jettyRequest.setHandled(true);
                response.sendError(HttpStatus.PROCESSING_102);
                response.sendError(HttpStatus.PROCESSING_102);
                response.setStatus(200);
                response.getOutputStream().print("OK");
            }
        });
        long idleTimeout = 10000;
        scenario.setRequestIdleTimeout(idleTimeout);

        scenario.client.getProtocolHandlers().put(new ProtocolHandler()
        {
            @Override
            public String getName()
            {
                return "Processing";
            }

            @Override
            public boolean accept(org.eclipse.jetty.client.api.Request request, Response response)
            {
                return response.getStatus() == HttpStatus.PROCESSING_102;
            }

            @Override
            public Response.Listener getResponseListener()
            {
                return new Response.Listener()
                {
                    @Override
                    public void onSuccess(Response response)
                    {
                        org.eclipse.jetty.client.api.Request request = response.getRequest();
                        HttpConversation conversation = ((HttpRequest)request).getConversation();
                        // Reset the conversation listeners, since we are going to receive another response code
                        conversation.updateResponseListeners(null);

                        HttpExchange exchange = conversation.getExchanges().peekLast();
                        if (exchange != null && response.getStatus() == HttpStatus.PROCESSING_102)
                        {
                            // All good, continue.
                            exchange.resetResponse();
                            exchange.proceed(null);
                        }
                        else
                        {
                            throw new IllegalStateException("should not have accepted");
                        }
                    }
                };
            }
        });

        CountDownLatch complete = new CountDownLatch(1);
        AtomicReference<Response> response = new AtomicReference<>();
        BufferingResponseListener listener = new BufferingResponseListener()
        {
            @Override
            public void onComplete(Result result)
            {
                response.set(result.getResponse());
                complete.countDown();
            }
        };
        scenario.client.newRequest(scenario.newURI())
            .method("GET")
            .headers(headers -> headers.put(HttpHeader.EXPECT, HttpHeaderValue.PROCESSING))
            .timeout(10, TimeUnit.SECONDS)
            .send(listener);

        assertTrue(complete.await(10, TimeUnit.SECONDS));
        assertThat(response.get().getStatus(), is(200));
        assertThat(listener.getContentAsString(), is("OK"));
    }

    @ParameterizedTest
    @ArgumentsSource(TransportProvider.class)
    public void test103EarlyHint(Transport transport) throws Exception
    {
        init(transport);
        scenario.start(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request jettyRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                jettyRequest.setHandled(true);
                response.setHeader("Hint", "one");
                response.sendError(HttpStatus.EARLY_HINT_103);
                response.setHeader("Hint", "two");
                response.sendError(HttpStatus.EARLY_HINT_103);
                response.setHeader("Hint", "three");
                response.setStatus(200);
                response.getOutputStream().print("OK");
            }
        });
        long idleTimeout = 10000;
        scenario.setRequestIdleTimeout(idleTimeout);

        List<String> hints = new CopyOnWriteArrayList<>();
        scenario.client.getProtocolHandlers().put(new ProtocolHandler()
        {
            @Override
            public String getName()
            {
                return "EarlyHint";
            }

            @Override
            public boolean accept(org.eclipse.jetty.client.api.Request request, Response response)
            {
                return response.getStatus() == HttpStatus.EARLY_HINT_103;
            }

            @Override
            public Response.Listener getResponseListener()
            {
                return new Response.Listener()
                {
                    @Override
                    public void onSuccess(Response response)
                    {
                        org.eclipse.jetty.client.api.Request request = response.getRequest();
                        HttpConversation conversation = ((HttpRequest)request).getConversation();
                        // Reset the conversation listeners, since we are going to receive another response code
                        conversation.updateResponseListeners(null);

                        HttpExchange exchange = conversation.getExchanges().peekLast();
                        if (exchange != null && response.getStatus() == HttpStatus.EARLY_HINT_103)
                        {
                            // All good, continue.
                            System.err.println("onSuccess\n" + response.getHeaders());
                            hints.add(response.getHeaders().get("Hint"));
                            exchange.resetResponse();
                            exchange.proceed(null);
                        }
                        else
                        {
                            throw new IllegalStateException("should not have accepted");
                        }
                    }
                };
            }
        });

        CountDownLatch complete = new CountDownLatch(1);
        AtomicReference<Response> response = new AtomicReference<>();
        BufferingResponseListener listener = new BufferingResponseListener()
        {
            @Override
            public void onComplete(Result result)
            {
                hints.add(result.getResponse().getHeaders().get("Hint"));
                response.set(result.getResponse());
                complete.countDown();
            }
        };
        scenario.client.newRequest(scenario.newURI())
            .method("GET")
            .timeout(10, TimeUnit.SECONDS)
            .send(listener);

        assertTrue(complete.await(10, TimeUnit.SECONDS));
        assertThat(response.get().getStatus(), is(200));
        assertThat(listener.getContentAsString(), is("OK"));
        assertThat(hints, contains("one", "two", "three"));
    }
}
