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

package org.eclipse.jetty.server;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.Socket;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.io.ArrayByteBufferPool;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.util.log.StacklessLogging;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

public abstract class AbstractHttpTest
{
    private static final Set<String> __noBodyCodes = new HashSet<>(Arrays.asList("100", "101", "102", "204", "304"));

    protected static Server server;
    protected static ServerConnector connector;
    private StacklessLogging stacklessChannelLogging;

    @BeforeEach
    public void setUp() throws Exception
    {
        server = new Server();
        connector = new ServerConnector(server, null, null, new ArrayByteBufferPool(64, 2048, 64 * 1024), 1, 1, new HttpConnectionFactory());
        connector.setIdleTimeout(100000);

        server.addConnector(connector);
        stacklessChannelLogging = new StacklessLogging(HttpChannel.class);
    }

    @AfterEach
    public void tearDown() throws Exception
    {
        server.stop();
        stacklessChannelLogging.close();
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

                HttpTester.Response response = new HttpTester.Response();
                HttpTester.Input input = HttpTester.from(socket.getInputStream());
                HttpTester.parseResponse(input, response);

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

    protected class ThrowExceptionOnDemandHandler extends AbstractHandler
    {
        private final boolean throwException;
        private volatile Throwable failure;

        protected ThrowExceptionOnDemandHandler(boolean throwException)
        {
            this.throwException = throwException;
        }

        @Override
        public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
        {
            if (throwException)
                throw new TestCommitException();
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
