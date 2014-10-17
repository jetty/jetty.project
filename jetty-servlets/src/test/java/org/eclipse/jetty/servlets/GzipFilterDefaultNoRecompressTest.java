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

package org.eclipse.jetty.servlets;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;

import javax.servlet.Filter;

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
    @SuppressWarnings("deprecation")
    @Parameters
    public static List<Object[]> data()
    {
        return Arrays.asList(new Object[][]
        {
                // Some already compressed files
           /* 00 */     { GzipFilter.class, "test_quotes.gz", "application/gzip", GzipFilter.GZIP },
           /* 01 */      { GzipFilter.class, "test_quotes.bz2", "application/bzip2", GzipFilter.GZIP },
           /* 02 */     { GzipFilter.class, "test_quotes.zip", "application/zip", GzipFilter.GZIP },
           /* 03 */     { GzipFilter.class, "test_quotes.rar", "application/octet-stream", GzipFilter.GZIP },
                // Some images (common first)
           /* 04 */     { GzipFilter.class, "jetty_logo.png", "image/png", GzipFilter.GZIP },
           /* 05 */     { GzipFilter.class, "jetty_logo.gif", "image/gif", GzipFilter.GZIP },
           /* 06 */     { GzipFilter.class, "jetty_logo.jpeg", "image/jpeg", GzipFilter.GZIP },
           /* 07 */     { GzipFilter.class, "jetty_logo.jpg", "image/jpeg", GzipFilter.GZIP },
                // Lesser encountered images (usually found being requested from non-browser clients)
           /* 08 */     { GzipFilter.class, "jetty_logo.bmp", "image/bmp", GzipFilter.GZIP },
           /* 09 */     { GzipFilter.class, "jetty_logo.tga", "application/tga", GzipFilter.GZIP },
           /* 10 */     { GzipFilter.class, "jetty_logo.tif", "image/tiff", GzipFilter.GZIP },
           /* 11 */     { GzipFilter.class, "jetty_logo.tiff", "image/tiff", GzipFilter.GZIP },
           /* 12 */     { GzipFilter.class, "jetty_logo.xcf", "image/xcf", GzipFilter.GZIP },
           /* 13 */     { GzipFilter.class, "jetty_logo.jp2", "image/jpeg2000", GzipFilter.GZIP },
                //qvalue disables compression
           /* 14 */     { GzipFilter.class, "test_quotes.txt", "text/plain", GzipFilter.GZIP+";q=0"},
           /* 15 */     { GzipFilter.class, "test_quotes.txt", "text/plain", GzipFilter.GZIP+"; q =    0 "},
                
                
                // Some already compressed files
           /* 16 */     { GzipFilter.class, "test_quotes.gz", "application/gzip", GzipFilter.GZIP },
           /* 17 */     { GzipFilter.class, "test_quotes.bz2", "application/bzip2", GzipFilter.GZIP },
           /* 18 */     { GzipFilter.class, "test_quotes.zip", "application/zip", GzipFilter.GZIP },
           /* 19 */     { GzipFilter.class, "test_quotes.rar", "application/octet-stream", GzipFilter.GZIP },
                // Some images (common first)
           /* 20 */     { GzipFilter.class, "jetty_logo.png", "image/png", GzipFilter.GZIP },
           /* 21 */     { GzipFilter.class, "jetty_logo.gif", "image/gif", GzipFilter.GZIP },
           /* 22 */     { GzipFilter.class, "jetty_logo.jpeg", "image/jpeg", GzipFilter.GZIP },
           /* 23 */     { GzipFilter.class, "jetty_logo.jpg", "image/jpeg", GzipFilter.GZIP },
                // Lesser encountered images (usually found being requested from non-browser clients)
           /* 24 */     { GzipFilter.class, "jetty_logo.bmp", "image/bmp", GzipFilter.GZIP },
           /* 25 */     { GzipFilter.class, "jetty_logo.tga", "application/tga", GzipFilter.GZIP },
           /* 26 */     { GzipFilter.class, "jetty_logo.tif", "image/tiff", GzipFilter.GZIP },
           /* 27 */     { GzipFilter.class, "jetty_logo.tiff", "image/tiff", GzipFilter.GZIP },
           /* 28 */     { GzipFilter.class, "jetty_logo.xcf", "image/xcf", GzipFilter.GZIP },
           /* 29 */     { GzipFilter.class, "jetty_logo.jp2", "image/jpeg2000", GzipFilter.GZIP },
                // qvalue disables compression
           /* 30 */     { GzipFilter.class, "test_quotes.txt", "text/plain", GzipFilter.GZIP+";q=0"},
           /* 31 */     { GzipFilter.class, "test_quotes.txt", "text/plain", GzipFilter.GZIP+"; q =    0 "}
        });
    }

    @Rule
    public TestingDir testingdir = new TestingDir();

    private Class<? extends Filter> testFilter;
    private String alreadyCompressedFilename;
    private String expectedContentType;
    private String compressionType;

    public GzipFilterDefaultNoRecompressTest(Class<? extends Filter> testFilter,String testFilename, String expectedContentType, String compressionType)
    {
        this.testFilter = testFilter;
        this.alreadyCompressedFilename = testFilename;
        this.expectedContentType = expectedContentType;
        this.compressionType = compressionType;
    }

    @Test
    public void testNotGzipFiltered_Default_AlreadyCompressed() throws Exception
    {
        GzipTester tester = new GzipTester(testingdir, compressionType);
        tester.setGzipFilterClass(testFilter);

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
