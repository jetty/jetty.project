//
//  ========================================================================
//  Copyright (c) 1995-2019 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.webapp;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.DefaultHandler;
import org.eclipse.jetty.server.handler.HandlerList;
import org.eclipse.jetty.toolchain.test.FS;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.resource.PathResource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

public class HugeResourceTest
{
    private static final long KB = 1024;
    private static final long MB = 1024 * KB;
    private static final long GB = 1024 * MB;
    public static Path hugeStaticBase;
    public static Path outputDir;

    public Server server;

    @BeforeAll
    public static void prepareStaticFiles() throws IOException
    {
        hugeStaticBase = MavenTestingUtils.getTargetTestingPath(HugeResourceTest.class.getSimpleName() + "-huge-static-base");
        FS.ensureDirExists(hugeStaticBase);

        makeStaticFile(hugeStaticBase.resolve("test-1g.dat"), 1 * GB);
        makeStaticFile(hugeStaticBase.resolve("test-4g.dat"), 4 * GB);
        makeStaticFile(hugeStaticBase.resolve("test-10g.dat"), 10 * GB);

        outputDir = MavenTestingUtils.getTargetTestingPath(HugeResourceTest.class.getSimpleName() + "-huge-static-outputdir");
        FS.ensureEmpty(outputDir);
    }

    @AfterAll
    public static void cleanupHugeStaticFiles()
    {
        FS.ensureDeleted(hugeStaticBase);
        FS.ensureDeleted(outputDir);
    }

    private static void makeStaticFile(Path staticFile, long size) throws IOException
    {
        byte[] buf = new byte[(int)(1 * MB)];
        Arrays.fill(buf, (byte)'x');
        ByteBuffer src = ByteBuffer.wrap(buf);

        if (Files.exists(staticFile) && Files.size(staticFile) == size)
        {
            // all done, nothing left to do.
            return;
        }

        System.err.printf("Creating %,d byte file: %s ...%n", size, staticFile.getFileName());
        try (SeekableByteChannel channel = Files.newByteChannel(staticFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING))
        {
            long remaining = size;
            while (remaining > 0)
            {
                ByteBuffer slice = src.slice();
                int len = buf.length;
                if (remaining < Integer.MAX_VALUE)
                {
                    len = Math.min(buf.length, (int)remaining);
                    slice.limit(len);
                }

                channel.write(slice);
                remaining -= len;
            }
        }
        System.err.println(" Done");
    }

    @BeforeEach
    public void startServer() throws Exception
    {
        server = new Server();
        ServerConnector connector = new ServerConnector(server);
        connector.setPort(0);
        server.addConnector(connector);

        WebAppContext context = new WebAppContext();
        context.setContextPath("/");
        context.setBaseResource(new PathResource(hugeStaticBase));

        HandlerList handlers = new HandlerList();
        handlers.addHandler(context);
        handlers.addHandler(new DefaultHandler());

        server.setHandler(handlers);
        server.start();
    }

    @AfterEach
    public void stopServer() throws Exception
    {
        server.stop();
    }

    @Test
    public void testDownload_1G() throws IOException
    {
        download(server.getURI().resolve("/test-1g.dat"), 1 * GB);
    }

    @Test
    public void testDownload_4G() throws IOException
    {
        download(server.getURI().resolve("/test-4g.dat"), 4 * GB);
    }

    @Test
    public void testDownload_10G() throws IOException
    {
        download(server.getURI().resolve("/test-10g.dat"), 10 * GB);
    }

    private void download(URI destUri, long expectedSize) throws IOException
    {
        HttpURLConnection http = (HttpURLConnection)destUri.toURL().openConnection();
        assertThat("HTTP Response Code", http.getResponseCode(), is(200));

        dumpResponseHeaders(http);

        // if a Content-Length is provided, test it
        String contentLength = http.getHeaderField("Content-Length");
        if (contentLength != null)
        {
            long contentLengthLong = Long.parseLong(contentLength);
            assertThat("Http Response Header: \"Content-Length: " + contentLength + "\"", contentLengthLong, is(expectedSize));
        }

        // Download the file
        String filename = destUri.getPath();
        int idx = filename.lastIndexOf('/');
        if (idx >= 0)
        {
            filename = filename.substring(idx + 1);
        }

        Path outputFile = outputDir.resolve(filename);
        try (OutputStream out = Files.newOutputStream(outputFile);
             InputStream in = http.getInputStream())
        {
            IO.copy(in, out);
        }

        // Verify the file download size
        assertThat("Downloaded Files Size: " + filename, Files.size(outputFile), is(expectedSize));
    }

    private void dumpResponseHeaders(HttpURLConnection http)
    {
        int i = 0;
        String value;
        while ((value = http.getHeaderField(i)) != null)
        {
            String key = http.getHeaderFieldKey(i);
            System.err.printf("  %s: %s%n", key, value);
            i++;
        }
    }
}
