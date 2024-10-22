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
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.Socket;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.io.ArrayByteBufferPool;
import org.eclipse.jetty.logging.StacklessLogging;
import org.eclipse.jetty.server.internal.HttpChannelState;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.component.LifeCycle;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

import static org.awaitility.Awaitility.await;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertNotNull;

public abstract class AbstractHttpTest
{
    private static final Set<String> __noBodyCodes = new HashSet<>(Arrays.asList("100", "101", "102", "204", "304"));

    protected static Server server;
    protected static ServerConnector connector;
    private StacklessLogging stacklessChannelLogging;
    private ArrayByteBufferPool.Tracking bufferPool;

    @BeforeEach
    public void setUp() throws Exception
    {
        server = new Server();
        bufferPool = new ArrayByteBufferPool.Tracking(64, 2048, 64 * 1024);
        connector = new ServerConnector(server, null, null, bufferPool, 1, 1, new HttpConnectionFactory());
        connector.setIdleTimeout(100000);

        server.addConnector(connector);
        stacklessChannelLogging = new StacklessLogging(HttpChannelState.class);
    }

    @AfterEach
    public void tearDown() throws Exception
    {
        try
        {
            await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> assertThat("Server leaks: " + bufferPool.dumpLeaks(), bufferPool.getLeaks().size(), is(0)));
        }
        finally
        {
            LifeCycle.stop(server);
            IO.close(stacklessChannelLogging);
        }
    }

    protected HttpTester.Response executeRequest(HttpVersion httpVersion) throws URISyntaxException, IOException
    {
        try (Socket socket = new Socket("localhost", connector.getLocalPort()))
        {
            socket.setSoTimeout((int)connector.getIdleTimeout());

            try (PrintWriter writer = new PrintWriter(new OutputStreamWriter(socket.getOutputStream())))
            {
                writer.write("GET / " + httpVersion.asString() + "\r\n");
                writer.write("Host: localhost\r\n");
                writer.write("\r\n");
                writer.flush();

                HttpTester.Input input = HttpTester.from(socket.getInputStream());
                HttpTester.Response response = HttpTester.parseResponse(input);
                assertNotNull(response);

                if (httpVersion.is("HTTP/1.1") &&
                    response.isComplete() &&
                    response.get("content-length") == null &&
                    response.get("transfer-encoding") == null &&
                    !__noBodyCodes.contains(response.getStatus()))
                    assertThat("If HTTP/1.1 response doesn't contain transfer-encoding or content-length headers, " +
                        "it should contain connection:close", response.get("connection"), is("close"));
                return response;
            }
        }
    }

    protected static class TestCommitException extends IllegalStateException
    {
        public TestCommitException()
        {
            super("Thrown by test");
        }
    }

    protected class ThrowExceptionOnDemandHandler extends Handler.Abstract.NonBlocking
    {
        private final boolean throwException;
        private volatile Throwable failure;

        protected ThrowExceptionOnDemandHandler(boolean throwException)
        {
            this.throwException = throwException;
        }

        @Override
        public boolean handle(Request request, Response response, Callback callback) throws Exception
        {
            if (throwException)
                throw new TestCommitException();
            callback.succeeded();
            return true;
        }

        protected void markFailed(Throwable x)
        {
            this.failure = x;
        }

        public Throwable failure()
        {
            return failure;
        }
    }
}
