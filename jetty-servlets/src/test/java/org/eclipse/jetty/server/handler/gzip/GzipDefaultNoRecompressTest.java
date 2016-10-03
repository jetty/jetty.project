//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.server.handler.gzip;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;

import org.eclipse.jetty.servlet.DefaultServlet;
import org.eclipse.jetty.toolchain.test.IO;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.toolchain.test.TestingDir;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

/**
 * Tests {@link GzipHandler} in combination with {@link DefaultServlet} for ability to configure {@link GzipHandler} to
 * ignore recompress situations from upstream.
 */
@RunWith(Parameterized.class)
public class GzipDefaultNoRecompressTest
{
    @Parameters
    public static List<Object[]> data()
    {
        return Arrays.asList(new Object[][]
        {
            // Some already compressed files
                { "test_quotes.gz", "application/gzip"  , GzipHandler.GZIP },
                { "test_quotes.br", "application/brotli"  , GzipHandler.GZIP },
                { "test_quotes.bz2", "application/bzip2", GzipHandler.GZIP },
                { "test_quotes.zip", "application/zip"  , GzipHandler.GZIP },
                { "test_quotes.rar", "application/x-rar-compressed", GzipHandler.GZIP },
            // Some images (common first)
                { "jetty_logo.png", "image/png", GzipHandler.GZIP},
                { "jetty_logo.gif", "image/gif", GzipHandler.GZIP},
                { "jetty_logo.jpeg", "image/jpeg", GzipHandler.GZIP},
                { "jetty_logo.jpg", "image/jpeg", GzipHandler.GZIP},
            // Lesser encountered images (usually found being requested from non-browser clients)
                { "jetty_logo.bmp", "image/bmp", GzipHandler.GZIP },
                { "jetty_logo.tif", "image/tiff", GzipHandler.GZIP },
                { "jetty_logo.tiff", "image/tiff", GzipHandler.GZIP },
                { "jetty_logo.xcf", "image/xcf", GzipHandler.GZIP },
                { "jetty_logo.jp2", "image/jpeg2000", GzipHandler.GZIP },
            //qvalue disables compression
                { "test_quotes.txt", "text/plain", GzipHandler.GZIP+";q=0"},
                { "test_quotes.txt", "text/plain", GzipHandler.GZIP+"; q =    0 "},
                
        });
    }

    @Rule
    public TestingDir testingdir = new TestingDir();

    private String alreadyCompressedFilename;
    private String expectedContentType;
    private String compressionType;

    public GzipDefaultNoRecompressTest(String testFilename, String expectedContentType, String compressionType)
    {
        this.alreadyCompressedFilename = testFilename;
        this.expectedContentType = expectedContentType;
        this.compressionType = compressionType;
    }

    @Test
    public void testNotGzipHandlered_Default_AlreadyCompressed() throws Exception
    {
        GzipTester tester = new GzipTester(testingdir, compressionType);

        copyTestFileToServer(alreadyCompressedFilename);

        tester.setContentServlet(TestStaticMimeTypeServlet.class);

        try
        {
            tester.start();
            tester.assertIsResponseNotGziped(alreadyCompressedFilename,alreadyCompressedFilename + ".sha1",expectedContentType);
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
