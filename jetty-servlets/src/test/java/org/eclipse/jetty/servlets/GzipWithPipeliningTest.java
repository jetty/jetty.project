//
//  ========================================================================
//  Copyright (c) 1995-2012 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.servlets;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Collection;
import java.util.EnumSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;
import javax.servlet.DispatcherType;

import org.eclipse.jetty.server.NetworkConnector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.servlet.DefaultServlet;
import org.eclipse.jetty.servlet.FilterHolder;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.servlets.gzip.Hex;
import org.eclipse.jetty.servlets.gzip.NoOpOutputStream;
import org.eclipse.jetty.toolchain.test.IO;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.toolchain.test.TestingDir;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

/**
 * Test the effects of Gzip filtering when in the context of HTTP/1.1 Pipelining.
 */
@RunWith(Parameterized.class)
public class GzipWithPipeliningTest
{
    @Parameters
    public static Collection<String[]> data()
    {
        // Test different Content-Encoding header combinations. So implicitly testing that gzip is preferred oder deflate
        String[][] data = new String[][]
                {
                { GzipFilter.GZIP },
                { GzipFilter.DEFLATE + ", " + GzipFilter.GZIP },
                { GzipFilter.GZIP + ", " + GzipFilter.DEFLATE },
                { GzipFilter.DEFLATE }
                };

        return Arrays.asList(data);
    }

    @Rule
    public TestingDir testingdir = new TestingDir();

    private Server server;
    private URI serverUri;
    private String encodingHeader;


    public GzipWithPipeliningTest(String encodingHeader)
    {
        this.encodingHeader = encodingHeader;
    }

    @Before
    public void startServer() throws Exception
    {
        // Configure Server
        server = new Server(0);

        ServletContextHandler context = new ServletContextHandler();
        context.setContextPath("/");

        DefaultServlet servlet = new DefaultServlet();
        ServletHolder holder = new ServletHolder(servlet);
        holder.setInitParameter("resourceBase",MavenTestingUtils.getTestResourcesDir().getAbsolutePath());
        context.addServlet(holder,"/");

        FilterHolder filter = context.addFilter(GzipFilter.class,"/*", EnumSet.of(DispatcherType.REQUEST));
        filter.setInitParameter("mimeTypes","text/plain");

        server.setHandler(context);

        // Start Server
        server.start();

        NetworkConnector conn = (NetworkConnector)server.getConnectors()[0];
        String host = conn.getHost();
        if (host == null)
        {
            host = "localhost";
        }
        int port = conn.getLocalPort();
        serverUri = new URI(String.format("ws://%s:%d/",host,port));
        // System.out.printf("Server URI: %s%n",serverUri);
    }

    @After
    public void stopServer() throws Exception
    {
        server.stop();
    }

    @Test
    public void testGzipThenImagePipelining() throws Exception
    {
        testingdir.ensureEmpty();
        File outputDir = testingdir.getDir();

        PipelineHelper client = new PipelineHelper(serverUri, encodingHeader);

        try
        {
            File txtFile = MavenTestingUtils.getTestResourceFile("lots-of-fantasy-names.txt");
            File pngFile = MavenTestingUtils.getTestResourceFile("jetty_logo.png");

            // Size of content, as it exists on disk, without gzip compression.
            long rawsize = txtFile.length() + pngFile.length();
            assertThat("Ensure that we have sufficient file size to trigger chunking",rawsize,greaterThan(300000L));

            String respHeader;

            client.connect();

            // Request text that will be gzipped + chunked in the response
            client.issueGET("/lots-of-fantasy-names.txt",true, false);

            respHeader = client.readResponseHeader();
            //System.out.println("Response Header #1 --\n" + respHeader);
            String expectedEncodingHeader = encodingHeader.equals(GzipFilter.DEFLATE) ? GzipFilter.DEFLATE : GzipFilter.GZIP;
            assertThat("Content-Encoding should be gzipped",respHeader,containsString("Content-Encoding: " + expectedEncodingHeader + "\r\n"));
            assertThat("Transfer-Encoding should be chunked",respHeader,containsString("Transfer-Encoding: chunked\r\n"));

            // Raw output / gzipped, writted to disk (checked for sha1sum later)
            File rawOutputFile = new File(outputDir, "response-1.gz");
            FileOutputStream rawOutputStream = new FileOutputStream(rawOutputFile);

            long chunkSize = client.readChunkSize();
            //System.out.println("Chunk Size: " + chunkSize);

            // Read only 20% - intentionally a partial read.
            //System.out.println("Attempting to read partial content ...");
            int readBytes = client.readBody(rawOutputStream,(int)(chunkSize * 0.20f));
            //System.out.printf("Read %,d bytes%n",readBytes);

            // Issue another request
            client.issueGET("/jetty_logo.png",true, false);

            // Finish reading chunks
            //System.out.println("Finish reading remaining chunks ...");
            String line;
            chunkSize = chunkSize - readBytes;
            while (chunkSize > 0)
            {
                readBytes = client.readBody(rawOutputStream,(int)chunkSize);
                //System.out.printf("Read %,d bytes%n",readBytes);
                line = client.readLine();
                assertThat("Chunk delim should be an empty line with CR+LF",line,is(""));
                chunkSize = client.readChunkSize();
                //System.out.printf("Next Chunk: (0x%X) %,d bytes%n",chunkSize,chunkSize);
            }

            // Inter-pipeline delim
            line = client.readLine();
            assertThat("Inter-pipeline delim should be an empty line with CR+LF",line,is(""));

            // Sha1tracking for 1st Request
            MessageDigest digestTxt = MessageDigest.getInstance("SHA1");
            DigestOutputStream digesterTxt = new DigestOutputStream(new NoOpOutputStream(),digestTxt);

            // Decompress 1st request and calculate sha1sum
            IO.close(rawOutputStream);
            FileInputStream rawInputStream = new FileInputStream(rawOutputFile);
            InputStream uncompressedStream = null;
            if (GzipFilter.DEFLATE.equals(encodingHeader))
            {
                uncompressedStream = new InflaterInputStream(rawInputStream, new Inflater(true));
            }
            else
            {
                uncompressedStream = new GZIPInputStream(rawInputStream);
            }

            IO.copy(uncompressedStream, digesterTxt);

            // Read 2nd request http response header
            respHeader = client.readResponseHeader();
            //System.out.println("Response Header #2 --\n" + respHeader);
            assertThat("Content-Encoding should NOT be gzipped",respHeader,not(containsString("Content-Encoding: gzip\r\n")));
            assertThat("Transfer-Encoding should NOT be chunked",respHeader,not(containsString("Transfer-Encoding: chunked\r\n")));

            // Sha1tracking for 2nd Request
            MessageDigest digestImg = MessageDigest.getInstance("SHA1");
            DigestOutputStream digesterImg = new DigestOutputStream(new NoOpOutputStream(),digestImg);

            // Read 2nd request body
            int contentLength = client.getContentLength(respHeader);
            assertThat("Image Content Length",(long)contentLength,is(pngFile.length()));
            client.readBody(digesterImg,contentLength);

            // Validate checksums
            IO.close(rawOutputStream);
            assertChecksum("lots-of-fantasy-names.txt",digestTxt);
            IO.close(digesterImg);
            assertChecksum("jetty_logo.png",digestImg);
        }
        finally
        {
            client.disconnect();
        }
    }

    private void assertChecksum(String testResourceFile, MessageDigest digest) throws IOException
    {
        String expectedSha1 = loadSha1sum(testResourceFile + ".sha1");
        String actualSha1 = Hex.asHex(digest.digest());
        assertEquals(testResourceFile + " / SHA1Sum of content",expectedSha1,actualSha1);
    }

    private String loadSha1sum(String testResourceSha1Sum) throws IOException
    {
        File sha1File = MavenTestingUtils.getTestResourceFile(testResourceSha1Sum);
        String contents = IO.readToString(sha1File);
        Pattern pat = Pattern.compile("^[0-9A-Fa-f]*");
        Matcher mat = pat.matcher(contents);
        assertTrue("Should have found HEX code in SHA1 file: " + sha1File,mat.find());
        return mat.group();
    }

}
