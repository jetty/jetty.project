//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.server.handler;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.toolchain.test.SimpleRequest;
import org.eclipse.jetty.util.IO;
import org.hamcrest.Matchers;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Resource Handler test
 * 
 * TODO: increase the testing going on here
 */
public class ResourceHandlerTest
{
    private static String LN = System.getProperty("line.separator");
    private static Server _server;
    private static HttpConfiguration _config;
    private static ServerConnector _connector;
    private static ContextHandler _contextHandler;
    private static ResourceHandler _resourceHandler;

    @BeforeClass
    public static void setUp() throws Exception
    {
        File dir = MavenTestingUtils.getTargetFile("test-classes/simple");
        File huge = new File(dir,"huge.txt");
        File big = new File(dir,"big.txt");
        try (OutputStream out = new FileOutputStream(huge))
        {
            for (int i = 0; i < 100; i++)
            {
                try (InputStream in = new FileInputStream(big))
                {
                    IO.copy(in,out);
                }
            }
        }
        huge.deleteOnExit();

        // determine how the SCM of choice checked out the big.txt EOL
        // we can't just use whatever is the OS default.
        // because, for example, a windows system using git can be configured for EOL handling using
        // local, remote, file lists, patterns, etc, rendering assumptions about the OS EOL choice
        // wrong for unit tests.
        LN = System.getProperty("line.separator");
        try (BufferedReader reader = Files.newBufferedReader(big.toPath(),StandardCharsets.UTF_8))
        {
            // a buffer large enough to capture at least 1 EOL
            char cbuf[] = new char[128];
            reader.read(cbuf);
            String sample = new String(cbuf);
            if (sample.contains("\r\n"))
            {
                LN = "\r\n";
            }
            else if (sample.contains("\n\r"))
            {
                LN = "\n\r";
            }
            else
            {
                LN = "\n";
            }
        }

        _server = new Server();
        _config = new HttpConfiguration();
        _config.setOutputBufferSize(2048);
        _connector = new ServerConnector(_server,new HttpConnectionFactory(_config));

        _server.setConnectors(new Connector[] { _connector });

        _resourceHandler = new ResourceHandler();
        _resourceHandler.setMinAsyncContentLength(4096);
        _resourceHandler.setMinMemoryMappedContentLength(8192);

        _resourceHandler.setResourceBase(MavenTestingUtils.getTargetFile("test-classes/simple").getAbsolutePath());

        _contextHandler = new ContextHandler("/resource");
        _contextHandler.setHandler(_resourceHandler);
        _server.setHandler(_contextHandler);
        _server.start();
    }

    @AfterClass
    public static void tearDown() throws Exception
    {
        _server.stop();
    }

    @Before
    public void before()
    {
        _config.setOutputBufferSize(4096);
    }

    @Test
    public void testMissing() throws Exception
    {
        SimpleRequest sr = new SimpleRequest(new URI("http://localhost:" + _connector.getLocalPort()));
        Assert.assertNotNull("missing jetty.css",sr.getString("/resource/jetty-dir.css"));
    }

    @Test
    public void testSimple() throws Exception
    {
        SimpleRequest sr = new SimpleRequest(new URI("http://localhost:" + _connector.getLocalPort()));
        Assert.assertEquals("simple text",sr.getString("/resource/simple.txt"));
    }

    @Test
    public void testBigFile() throws Exception
    {
        _config.setOutputBufferSize(2048);
        SimpleRequest sr = new SimpleRequest(new URI("http://localhost:" + _connector.getLocalPort()));
        String response = sr.getString("/resource/big.txt");
        Assert.assertThat(response,Matchers.startsWith("     1\tThis is a big file"));
        Assert.assertThat(response,Matchers.endsWith("   400\tThis is a big file" + LN));
    }

    @Test
    public void testBigFileBigBuffer() throws Exception
    {
        _config.setOutputBufferSize(16 * 1024);
        SimpleRequest sr = new SimpleRequest(new URI("http://localhost:" + _connector.getLocalPort()));
        String response = sr.getString("/resource/big.txt");
        Assert.assertThat(response,Matchers.startsWith("     1\tThis is a big file"));
        Assert.assertThat(response,Matchers.endsWith("   400\tThis is a big file" + LN));
    }

    @Test
    public void testBigFileLittleBuffer() throws Exception
    {
        _config.setOutputBufferSize(8);
        SimpleRequest sr = new SimpleRequest(new URI("http://localhost:" + _connector.getLocalPort()));
        String response = sr.getString("/resource/big.txt");
        Assert.assertThat(response,Matchers.startsWith("     1\tThis is a big file"));
        Assert.assertThat(response,Matchers.endsWith("   400\tThis is a big file" + LN));
    }

    @Test
    public void testHuge() throws Exception
    {
        try (Socket socket = new Socket("localhost",_connector.getLocalPort());)
        {
            socket.getOutputStream().write("GET /resource/huge.txt HTTP/1.0\n\n".getBytes());
            Thread.sleep(1000);
            String response = IO.toString(socket.getInputStream());
            Assert.assertThat(response,Matchers.startsWith("HTTP/1.1 200 OK"));
            Assert.assertThat(response,Matchers.containsString("   400\tThis is a big file" + LN + "     1\tThis is a big file"));
            Assert.assertThat(response,Matchers.endsWith("   400\tThis is a big file" + LN));
        }
    }
}
