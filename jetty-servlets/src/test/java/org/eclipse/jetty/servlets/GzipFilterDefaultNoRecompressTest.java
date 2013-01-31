//
//  ========================================================================
//  Copyright (c) 1995-2013 Mort Bay Consulting Pty. Ltd.
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
import java.io.IOException;
import java.util.Arrays;
import java.util.List;

import org.eclipse.jetty.servlet.DefaultServlet;
import org.eclipse.jetty.servlet.FilterHolder;
import org.eclipse.jetty.servlets.gzip.GzipTester;
import org.eclipse.jetty.servlets.gzip.TestStaticMimeTypeServlet;
import org.eclipse.jetty.toolchain.test.IO;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.toolchain.test.TestingDir;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

/**
 * Tests {@link GzipFilter} in combination with {@link DefaultServlet} for ability to configure {@link GzipFilter} to
 * ignore recompress situations from upstream.
 */
@RunWith(Parameterized.class)
public class GzipFilterDefaultNoRecompressTest
{
    @Parameters
    public static List<Object[]> data()
    {
        return Arrays.asList(new Object[][]
        {
                // Some already compressed files
                { "test_quotes.gz", "application/gzip", GzipFilter.GZIP },
                { "test_quotes.bz2", "application/bzip2", GzipFilter.GZIP },
                { "test_quotes.zip", "application/zip", GzipFilter.GZIP },
                { "test_quotes.rar", "application/octet-stream", GzipFilter.GZIP },
                // Some images (common first)
                { "jetty_logo.png", "image/png", GzipFilter.GZIP },
                { "jetty_logo.gif", "image/gif", GzipFilter.GZIP },
                { "jetty_logo.jpeg", "image/jpeg", GzipFilter.GZIP },
                { "jetty_logo.jpg", "image/jpeg", GzipFilter.GZIP },
                // Lesser encountered images (usually found being requested from non-browser clients)
                { "jetty_logo.bmp", "image/bmp", GzipFilter.GZIP },
                { "jetty_logo.tga", "application/tga", GzipFilter.GZIP },
                { "jetty_logo.tif", "image/tiff", GzipFilter.GZIP },
                { "jetty_logo.tiff", "image/tiff", GzipFilter.GZIP },
                { "jetty_logo.xcf", "image/xcf", GzipFilter.GZIP },
                { "jetty_logo.jp2", "image/jpeg2000", GzipFilter.GZIP },
                //qvalue disables compression
                { "test_quotes.txt", "text/plain", GzipFilter.GZIP+";q=0"},
                { "test_quotes.txt", "text/plain", GzipFilter.GZIP+"; q =    0 "},
               

                // Same tests again for deflate
                // Some already compressed files
                { "test_quotes.gz", "application/gzip", GzipFilter.DEFLATE },
                { "test_quotes.bz2", "application/bzip2", GzipFilter.DEFLATE },
                { "test_quotes.zip", "application/zip", GzipFilter.DEFLATE },
                { "test_quotes.rar", "application/octet-stream", GzipFilter.DEFLATE },
                // Some images (common first)
                { "jetty_logo.png", "image/png", GzipFilter.DEFLATE },
                { "jetty_logo.gif", "image/gif", GzipFilter.DEFLATE },
                { "jetty_logo.jpeg", "image/jpeg", GzipFilter.DEFLATE },
                { "jetty_logo.jpg", "image/jpeg", GzipFilter.DEFLATE },
                // Lesser encountered images (usually found being requested from non-browser clients)
                { "jetty_logo.bmp", "image/bmp", GzipFilter.DEFLATE },
                { "jetty_logo.tga", "application/tga", GzipFilter.DEFLATE },
                { "jetty_logo.tif", "image/tiff", GzipFilter.DEFLATE },
                { "jetty_logo.tiff", "image/tiff", GzipFilter.DEFLATE },
                { "jetty_logo.xcf", "image/xcf", GzipFilter.DEFLATE },
                { "jetty_logo.jp2", "image/jpeg2000", GzipFilter.DEFLATE } });
    }

    @Rule
    public TestingDir testingdir = new TestingDir();

    private String alreadyCompressedFilename;
    private String expectedContentType;
    private String compressionType;

    public GzipFilterDefaultNoRecompressTest(String testFilename, String expectedContentType, String compressionType)
    {
        this.alreadyCompressedFilename = testFilename;
        this.expectedContentType = expectedContentType;
        this.compressionType = compressionType;
    }

    @Test
    public void testNotGzipFiltered_Default_AlreadyCompressed() throws Exception
    {
        GzipTester tester = new GzipTester(testingdir, compressionType);

        copyTestFileToServer(alreadyCompressedFilename);

        FilterHolder holder = tester.setContentServlet(TestStaticMimeTypeServlet.class);
        StringBuilder mimeTypes = new StringBuilder();
        mimeTypes.append("text/plain");
        holder.setInitParameter("mimeTypes",mimeTypes.toString());

        try
        {
            tester.start();
            tester.assertIsResponseNotGzipFiltered(alreadyCompressedFilename,alreadyCompressedFilename + ".sha1",expectedContentType);
        }
        finally
        {
            tester.stop();
        }
    }

    private void copyTestFileToServer(String testFilename) throws IOException
    {
        File testFile = MavenTestingUtils.getTestResourceFile(testFilename);
        File outFile = testingdir.getFile(testFilename);
        IO.copy(testFile,outFile);
    }
}
