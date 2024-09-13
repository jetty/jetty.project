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

package org.eclipse.jetty.test.client.transport;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import org.eclipse.jetty.client.ContentResponse;
import org.eclipse.jetty.client.Result;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.VirtualThreads;
import org.eclipse.jetty.util.thread.ThreadPool;
import org.eclipse.jetty.util.thread.VirtualThreadPool;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.condition.DisabledForJreRange;
import org.junit.jupiter.api.condition.JRE;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisabledForJreRange(max = JRE.JAVA_18)
public class VirtualThreadsTest extends AbstractTest
{
    @ParameterizedTest
    @MethodSource("transports")
    public void testHandlerInvokedOnVirtualThread(Transport transport) throws Exception
    {
        // No virtual thread support in FCGI server-side.
        Assumptions.assumeTrue(transport != Transport.FCGI);

        String virtualThreadsName = "green-";
        prepareServer(transport, new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                if (!VirtualThreads.isVirtualThread())
                    response.setStatus(HttpStatus.NOT_IMPLEMENTED_501);
                if (!Thread.currentThread().getName().startsWith(virtualThreadsName))
                    response.setStatus(HttpStatus.NOT_IMPLEMENTED_501);
                callback.succeeded();
                return true;
            }
        });
        ThreadPool threadPool = server.getThreadPool();
        if (threadPool instanceof VirtualThreads.Configurable)
        {
            Executor virtualThreadsExecutor = VirtualThreads.getNamedVirtualThreadsExecutor(virtualThreadsName);
            ((VirtualThreads.Configurable)threadPool).setVirtualThreadsExecutor(virtualThreadsExecutor);
        }
        server.start();
        startClient(transport);

        ContentResponse response = client.newRequest(newURI(transport))
            .timeout(5, TimeUnit.SECONDS)
            .send();

        assertEquals(HttpStatus.OK_200, response.getStatus(), " for transport " + transport);
    }

    @ParameterizedTest
    @MethodSource("transports")
    public void testClientListenersInvokedOnVirtualThread(Transport transport) throws Exception
    {
        startServer(transport, new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback) throws Exception
            {
                // Send only the headers.
                response.write(false, null, Callback.NOOP);
                // Wait to force the client to invoke the content
                // callback separately from the headers callback.
                Thread.sleep(500);
                // Send the content.
                Content.Sink.write(response, true, "hello", callback);
                return true;
            }
        });

        prepareClient(transport);
        VirtualThreads.Configurable executor = (VirtualThreads.Configurable)client.getExecutor();
        VirtualThreadPool vtp = new VirtualThreadPool();
        vtp.setName("green-");
        executor.setVirtualThreadsExecutor(vtp);
        client.start();

        for (int i = 0; i < 2; ++i)
        {
            AtomicReference<Result> resultRef = new AtomicReference<>();
            ConcurrentLinkedQueue<String> queue = new ConcurrentLinkedQueue<>();
            Consumer<String> verify = name -> queue.offer((VirtualThreads.isVirtualThread() ? "virtual" : "platform") + "-" + name);
            client.newRequest(newURI(transport))
                .onResponseBegin(r -> verify.accept("begin"))
                .onResponseHeaders(r -> verify.accept("headers"))
                .onResponseContent((r, b) -> verify.accept("content"))
                .onResponseSuccess(r -> verify.accept("success"))
                .onComplete(r -> verify.accept("complete"))
                .send(r ->
                {
                    verify.accept("send");
                    resultRef.set(r);
                });

            Result result = await().atMost(5, TimeUnit.SECONDS).until(resultRef::get, notNullValue());
            assertTrue(result.isSucceeded());
            assertEquals(HttpStatus.OK_200, result.getResponse().getStatus());
            queue.forEach(event -> assertTrue(event.startsWith("virtual"), event));
        }
    }
}
