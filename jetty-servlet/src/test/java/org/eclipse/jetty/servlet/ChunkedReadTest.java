//
//  ========================================================================
//  Copyright (c) 1995-2018 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.servlet;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Locale.US;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.toolchain.test.Hex;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.toolchain.test.Sha1Sum;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

public class ChunkedReadTest
{
    private static Server server;

    @BeforeClass
    public static void startServer() throws Exception
    {
        server = new Server();
        ServerConnector connector = new ServerConnector(server);
        connector.setPort(0);
        server.addConnector(connector);

        ServletContextHandler context = new ServletContextHandler();
        context.setContextPath("/");
        context.addServlet(ChunkedReadServlet.class, "/chunked/read");

        server.setHandler(context);
        server.start();
    }

    @AfterClass
    public static void stopServer() throws Exception
    {
        server.stop();
    }

    @Test
    public void testHttpUrlConnectionChunkedSend_Close() throws IOException
    {
        Path pngPath = MavenTestingUtils.getTestResourcePath("jetty-logo-shadow.png");
        Path pngSha1Path = MavenTestingUtils.getTestResourcePath("jetty-logo-shadow.png.sha1");

        HttpURLConnection http = (HttpURLConnection) server.getURI().resolve("/chunked/read").toURL().openConnection();
        http.setChunkedStreamingMode(4096);
        http.setDoOutput(true);
        try (OutputStream httpOut = http.getOutputStream();
             InputStream fileIn = Files.newInputStream(pngPath))
        {
            IO.copy(fileIn, httpOut);
            int status = http.getResponseCode();
            assertThat("Http Status Code", status, is(HttpURLConnection.HTTP_OK));
            try (InputStream httpIn = http.getInputStream())
            {
                String expectedSha = Sha1Sum.loadSha1(pngSha1Path.toFile());
                String response = IO.toString(httpIn, UTF_8);
                assertThat("Response Hashcode", response, is(expectedSha));
            }
        }
    }

    public static class ChunkedReadServlet extends HttpServlet
    {
        private static final Logger LOG = Log.getLogger(ChunkedReadServlet.class);

        @Override
        protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException
        {
            String te = req.getHeader("Transfer-Encoding");
            if (StringUtil.isBlank(te) || !te.equalsIgnoreCase("chunked"))
            {
                // Error out if "Transfer-Encoding: chunked" is not present on request.
                LOG.warn("Request Transfer-Encoding <{}> is not expected value of <chunked>", te);
                resp.setStatus(HttpServletResponse.SC_EXPECTATION_FAILED);
                return;
            }

            InputStream in = req.getInputStream();
            MessageDigest digest = newSha1Digest();
            try (NoOpOutputStream noop = new NoOpOutputStream();
                 DigestOutputStream digester = new DigestOutputStream(noop, digest))
            {
                IO.copy(in, digester);
                resp.setContentType("text/plain");
                resp.getWriter().print(Hex.asHex(digest.digest()).toLowerCase(US));
            }
        }

        private MessageDigest newSha1Digest() throws ServletException
        {
            try
            {
                return MessageDigest.getInstance("SHA1");
            }
            catch (NoSuchAlgorithmException e)
            {
                throw new ServletException("Unable to find SHA1 MessageDigest", e);
            }
        }
    }

    static class NoOpOutputStream extends OutputStream
    {
        @Override
        public void write(byte[] b) { }

        @Override
        public void write(byte[] b, int off, int len) { }

        @Override
        public void flush() { }

        @Override
        public void close() { }

        @Override
        public void write(int b) { }
    }
}
