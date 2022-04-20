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

package org.eclipse.jetty.ee9.servlet;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpUpgradeHandler;
import jakarta.servlet.http.WebConnection;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.DefaultHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.eclipse.jetty.util.StringUtil.CRLF;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.instanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Disabled // TODO
public class ServletUpgradeTest
{
    private static final Logger LOG = LoggerFactory.getLogger(ServletUpgradeTest.class);

    private Server server;
    private int port;

    @BeforeEach
    public void setUp() throws Exception
    {
        server = new Server();

        ServerConnector connector = new ServerConnector(server);
        server.addConnector(connector);

        ServletContextHandler contextHandler = new ServletContextHandler(ServletContextHandler.NO_SESSIONS);
        contextHandler.setContextPath("/");
        contextHandler.addServlet(new ServletHolder(new TestServlet()), "/TestServlet");

        org.eclipse.jetty.server.Handler.Collection handlers = new org.eclipse.jetty.server.Handler.Collection();
        handlers.setHandlers(contextHandler.getCoreContextHandler(), new DefaultHandler());
        server.setHandler(handlers);

        server.start();
        port = connector.getLocalPort();
    }

    @AfterEach
    public void tearDown() throws Exception
    {
        server.stop();
    }

    @Test
    public void upgradeTest() throws Exception
    {
        boolean passed1 = false;
        boolean passed2 = false;
        boolean passed3 = false;
        String expectedResponse1 = "TCKHttpUpgradeHandler.init";
        String expectedResponse2 = "onDataAvailable|Hello";
        String expectedResponse3 = "onDataAvailable|World";

        InputStream input = null;
        OutputStream output = null;
        Socket s = null;

        try
        {
            s = new Socket("localhost", port);
            output = s.getOutputStream();

            StringBuilder reqStr = new StringBuilder()
                .append("POST /TestServlet HTTP/1.1").append(CRLF)
                .append("User-Agent: Java/1.6.0_33").append(CRLF)
                .append("Host: localhost:").append(port).append(CRLF)
                .append("Accept: text/html, image/gif, image/jpeg, *; q=.2, */*; q=.2").append(CRLF)
                .append("Upgrade: YES").append(CRLF)
                .append("Connection: Upgrade").append(CRLF)
                .append("Content-type: application/x-www-form-urlencoded").append(CRLF)
                .append(CRLF);

            LOG.info("REQUEST=========" + reqStr);
            output.write(reqStr.toString().getBytes());

            LOG.info("Writing first chunk");
            writeChunk(output, "Hello");

            LOG.info("Writing second chunk");
            writeChunk(output, "World");

            LOG.info("Consuming the response from the server");

            // Consume the response from the server
            input = s.getInputStream();
            int len;
            byte[] b = new byte[1024];
            boolean receivedFirstMessage = false;
            boolean receivedSecondMessage = false;
            boolean receivedThirdMessage = false;
            StringBuilder sb = new StringBuilder();
            while ((len = input.read(b)) != -1)
            {
                String line = new String(b, 0, len);
                sb.append(line);
                LOG.info("==============Read from server:" + CRLF + sb + CRLF);
                if (passed1 = compareString(expectedResponse1, sb.toString()))
                {
                    LOG.info("==============Received first expected response!" + CRLF);
                    receivedFirstMessage = true;
                }
                if (passed2 = compareString(expectedResponse2, sb.toString()))
                {
                    LOG.info("==============Received second expected response!" + CRLF);
                    receivedSecondMessage = true;
                }
                if (passed3 = compareString(expectedResponse3, sb.toString()))
                {
                    LOG.info("==============Received third expected response!" + CRLF);
                    receivedThirdMessage = true;
                }
                LOG.info("receivedFirstMessage : " + receivedFirstMessage);
                LOG.info("receivedSecondMessage : " + receivedSecondMessage);
                LOG.info("receivedThirdMessage : " + receivedThirdMessage);
                if (receivedFirstMessage && receivedSecondMessage && receivedThirdMessage)
                {
                    break;
                }
            }
        }
        finally
        {
            try
            {
                if (input != null)
                {
                    LOG.info("Closing input...");
                    input.close();
                    LOG.info("Input closed.");
                }
            }
            catch (Exception ex)
            {
                LOG.error("Failed to close input:" + ex.getMessage(), ex);
            }

            try
            {
                if (output != null)
                {
                    LOG.info("Closing output...");
                    output.close();
                    LOG.info("Output closed .");
                }
            }
            catch (Exception ex)
            {
                LOG.error("Failed to close output:" + ex.getMessage(), ex);
            }

            try
            {
                if (s != null)
                {
                    LOG.info("Closing socket..." + CRLF);
                    s.close();
                    LOG.info("Socked closed.");
                }
            }
            catch (Exception ex)
            {
                LOG.error("Failed to close socket:" + ex.getMessage(), ex);
            }
        }

        assertTrue(passed1 && passed2 && passed3);
    }

    private static class TestServlet extends HttpServlet
    {
        public void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
        {
            if (request.getHeader("Upgrade") != null)
            {
                response.setStatus(101);
                response.setHeader("Upgrade", "YES");
                response.setHeader("Connection", "Upgrade");
                TestHttpUpgradeHandler handler = request.upgrade(TestHttpUpgradeHandler.class);
                assertThat(handler, instanceOf(TestHttpUpgradeHandler.class));
            }
            else
            {
                response.getWriter().println("No upgrade");
                response.getWriter().println("End of Test");
            }
        }
    }

    public static class TestHttpUpgradeHandler implements HttpUpgradeHandler
    {
        public TestHttpUpgradeHandler()
        {
        }

        @Override
        public void destroy()
        {
            LOG.debug("===============destroy");
        }

        @Override
        public void init(WebConnection wc)
        {
            try
            {
                ServletInputStream input = wc.getInputStream();
                ServletOutputStream output = wc.getOutputStream();
                TestReadListener readListener = new TestReadListener("/", input, output);
                input.setReadListener(readListener);
                output.println("===============TCKHttpUpgradeHandler.init");
                output.flush();
            }
            catch (Exception ex)
            {
                throw new RuntimeException(ex);
            }
        }
    }

    private static class TestReadListener implements ReadListener
    {
        private final ServletInputStream input;
        private final ServletOutputStream output;
        private final String delimiter;

        TestReadListener(String del, ServletInputStream in, ServletOutputStream out)
        {
            input = in;
            output = out;
            delimiter = del;
        }

        public void onAllDataRead()
        {
            try
            {
                output.println("=onAllDataRead");
                output.close();
            }
            catch (Exception ex)
            {
                throw new IllegalStateException(ex);
            }
        }

        public void onDataAvailable()
        {
            try
            {
                output.println("=onDataAvailable");
                StringBuilder sb = new StringBuilder();
                int len;
                byte[] b = new byte[1024];
                while (input.isReady() && (len = input.read(b)) != -1)
                {
                    String data = new String(b, 0, len);
                    sb.append(data);
                }
                output.println(delimiter + sb);
                output.flush();
            }
            catch (Exception ex)
            {
                throw new IllegalStateException(ex);
            }
        }

        public void onError(final Throwable t)
        {
            LOG.error("TestReadListener error", t);
        }
    }

    private static boolean compareString(String expected, String actual)
    {
        String[] listExpected = expected.split("[|]");
        boolean found = true;
        for (int i = 0, n = listExpected.length, startIdx = 0, bodyLength = actual.length(); i < n; i++)
        {
            String search = listExpected[i];
            if (startIdx >= bodyLength)
            {
                startIdx = bodyLength;
            }

            int searchIdx = actual.toLowerCase().indexOf(search.toLowerCase(), startIdx);

            LOG.debug("[ServletTestUtil] Scanning response for " + "search string: '" + search + "' starting at index " + "location: " + startIdx);
            if (searchIdx < 0)
            {
                found = false;
                String s = "[ServletTestUtil] Unable to find the following " +
                    "search string in the server's " +
                    "response: '" + search + "' at index: " +
                    startIdx +
                    "\n[ServletTestUtil] Server's response:\n" +
                    "-------------------------------------------\n" +
                    actual +
                    "\n-------------------------------------------\n";
                LOG.debug(s);
                break;
            }

            LOG.debug("[ServletTestUtil] Found search string: '" + search + "' at index '" + searchIdx + "' in the server's " + "response");
            // the new searchIdx is the old index plus the lenght of the
            // search string.
            startIdx = searchIdx + search.length();
        }
        return found;
    }

    private static void writeChunk(OutputStream out, String data) throws IOException
    {
        if (data != null)
        {
            out.write(data.getBytes());
        }
        out.flush();
    }
}
