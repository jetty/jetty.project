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

package org.eclipse.jetty.server;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.URI;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.logging.StacklessLogging;
import org.eclipse.jetty.server.internal.HttpConnection;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.component.LifeCycle;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;

/**
 * The purpose of this testcase is to ensure that we handle the various
 * Response complete flows when dealing with errors and failures.
 */
public class ResponseCompleteTest
{
    private static final Logger LOG = LoggerFactory.getLogger(ResponseCompleteTest.class);
    private static final byte[] GET_REQUEST_BYTES = """
        GET / HTTP/1.1
        Host: local
        Connection: close
        
        """.getBytes(UTF_8);
    private Server server;

    private Server startServer(HttpConnectionFactory httpConnectionFactory, Handler handler) throws Exception
    {
        server = new Server();
        ServerConnector connector = new ServerConnector(server, 1, 1, httpConnectionFactory);
        connector.setPort(0);
        server.addConnector(connector);
        server.setHandler(handler);
        server.start();
        return server;
    }

    @AfterEach
    public void stopServer()
    {
        LifeCycle.stop(server);
    }

    /**
     * The Handler returns true, and doesn't throw.
     * Callback not yet completing when response fails in different thread.
     */
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    public void testHandleCallbackNotCompletingYet(boolean throwFromHandler) throws Exception
    {
        AtomicReference<Callback> callbackAtomicReference = new AtomicReference<>();
        startServer(new HttpConnectionFactory(), new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback) throws Exception
            {
                response.setStatus(200);
                callbackAtomicReference.set(callback);
                if (throwFromHandler)
                    throw new Exception("Test");
                return true;
            }
        });

        try (Socket client = connectToServer();
             OutputStream output = client.getOutputStream();
             InputStream input = client.getInputStream();
             StacklessLogging ignore = new StacklessLogging(Response.class))
        {
            output.write(GET_REQUEST_BYTES);
            output.flush();

            Thread.sleep(1000);
            callbackAtomicReference.get().failed(new Exception("Test"));

            HttpTester.Response response = HttpTester.parseResponse(input);
            assertThat(response.getStatus(), is(500));
            assertThat(response.getContent(), containsString("<h2>HTTP ERROR 500 java.lang.Exception: Test</h2>"));
        }
    }

    /**
     * The Handler returns true, and doesn't throw.
     * Callback is completing when response fails in different thread.
     */
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    public void testHandleCallbackCompleting(boolean throwFromHandler) throws Exception
    {
        CountDownLatch handleLatch = new CountDownLatch(1);
        CountDownLatch failureLatch = new CountDownLatch(1);
        startServer(
            new HttpConnectionFactory()
            {
                @Override
                public Connection newConnection(Connector connector, EndPoint endPoint)
                {
                    HttpConnection connection = new HttpConnection(getHttpConfiguration(), connector, endPoint, isRecordHttpComplianceViolations())
                    {
                        @Override
                        protected HttpStreamOverHTTP1 newHttpStream(String method, String uri, HttpVersion version)
                        {
                            return new HttpStreamOverHTTP1(method, uri, version)
                            {
                                @Override
                                public Throwable consumeAvailable()
                                {
                                    /*
                                     * Wait till callback is complete
                                     *
                                     * We rely on the existence of consumeAvailable in ChannelCallback.failure()
                                     */

                                    try
                                    {
                                        handleLatch.countDown();
                                        failureLatch.await();
                                        LOG.debug("consumeAvailable allowed to continue");
                                        return super.consumeAvailable();
                                    }
                                    catch (InterruptedException e)
                                    {
                                        throw new RuntimeException(e);
                                    }
                                }
                            };
                        }
                    };
                    connection.setUseInputDirectByteBuffers(isUseInputDirectByteBuffers());
                    connection.setUseOutputDirectByteBuffers(isUseOutputDirectByteBuffers());
                    return configure(connection, connector, endPoint);
                }
            }, new Handler.Abstract()
            {
                @Override
                public boolean handle(Request request, Response response, Callback callback) throws Exception
                {
                    response.setStatus(200);
                    getServer().getThreadPool().execute(() ->
                    {
                        LOG.debug("handle.threadPool.execute() -> callback.failed() being called");
                        callback.failed(new Exception("Test-Threaded"));
                    });
                    handleLatch.await();
                    if (throwFromHandler)
                        throw new Exception("Test-Handler");
                    return true;
                }
            });

        try (Socket client = connectToServer();
             OutputStream output = client.getOutputStream();
             InputStream input = client.getInputStream();
             StacklessLogging ignore = new StacklessLogging(Response.class))
        {
            output.write(GET_REQUEST_BYTES);
            output.flush();

            Thread.sleep(1000); // ensure we are fully out of the HandlerInvoker.run()
            failureLatch.countDown();

            LOG.debug("Reading response");
            String rawResponse = IO.toString(input, UTF_8);
            assertThat("Raw Response Length", rawResponse.length(), greaterThan(0));
            HttpTester.Response response = HttpTester.parseResponse(rawResponse);
            assertThat(response.getStatus(), is(500));
            assertThat(response.getContent(), containsString("<h2>HTTP ERROR 500 java.lang.Exception: Test-Threaded</h2>"));
        }
    }

    /**
     * The Handler returns true.
     * Callback is completed when response fails in different thread.
     */
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    public void testHandleCallbackCompleted(boolean throwFromHandler) throws Exception
    {
        startServer(new HttpConnectionFactory(), new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback) throws Exception
            {
                response.setStatus(200);
                callback.failed(new Exception("Test"));
                if (throwFromHandler)
                    throw new Exception("Test");
                return true;
            }
        });

        try (Socket client = connectToServer();
             OutputStream output = client.getOutputStream();
             InputStream input = client.getInputStream();
             StacklessLogging ignore = new StacklessLogging(Response.class))
        {
            output.write(GET_REQUEST_BYTES);
            output.flush();

            HttpTester.Response response = HttpTester.parseResponse(input);
            assertThat(response.getStatus(), is(500));
            assertThat(response.getContent(), containsString("<h2>HTTP ERROR 500 java.lang.Exception: Test</h2>"));
        }
    }

    private Socket connectToServer() throws IOException
    {
        URI serverURI = server.getURI();
        return new Socket(serverURI.getHost(), serverURI.getPort());
    }
}
