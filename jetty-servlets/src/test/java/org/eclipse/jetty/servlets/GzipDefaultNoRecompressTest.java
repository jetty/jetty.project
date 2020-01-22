//
// ========================================================================
// Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under
// the terms of the Eclipse Public License 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0
//
// This Source Code may also be made available under the following
// Secondary Licenses when the conditions for such availability set
// forth in the Eclipse Public License, v. 2.0 are satisfied:
// the Apache License v2.0 which is available at
// https://www.apache.org/licenses/LICENSE-2.0
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.servlets;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.stream.Stream;

import org.eclipse.jetty.server.handler.gzip.GzipHandler;
import org.eclipse.jetty.servlet.DefaultServlet;
import org.eclipse.jetty.toolchain.test.IO;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDir;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDirExtension;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Tests {@link GzipHandler} in combination with {@link DefaultServlet} for ability to configure {@link GzipHandler} to
 * ignore recompress situations from upstream.
 */
@ExtendWith(WorkDirExtension.class)
public class GzipDefaultNoRecompressTest
{
    public static Stream<Arguments> data()
    {
        return Arrays.asList(new Object[][]
            {
                // Some already compressed files
                {"test_quotes.gz", "application/gzip", GzipHandler.GZIP},
                {"test_quotes.br", "application/brotli", GzipHandler.GZIP},
                {"test_quotes.bz2", "application/bzip2", GzipHandler.GZIP},
                {"test_quotes.zip", "application/zip", GzipHandler.GZIP},
                {"test_quotes.rar", "application/x-rar-compressed", GzipHandler.GZIP},
                // Some images (common first)
                {"jetty_logo.png", "image/png", GzipHandler.GZIP},
                {"jetty_logo.gif", "image/gif", GzipHandler.GZIP},
                {"jetty_logo.jpeg", "image/jpeg", GzipHandler.GZIP},
                {"jetty_logo.jpg", "image/jpeg", GzipHandler.GZIP},
                // Lesser encountered images (usually found being requested from non-browser clients)
                {"jetty_logo.bmp", "image/bmp", GzipHandler.GZIP},
                {"jetty_logo.tif", "image/tiff", GzipHandler.GZIP},
                {"jetty_logo.tiff", "image/tiff", GzipHandler.GZIP},
                {"jetty_logo.xcf", "image/xcf", GzipHandler.GZIP},
                {"jetty_logo.jp2", "image/jpeg2000", GzipHandler.GZIP},
                //qvalue disables compression
                {"test_quotes.txt", "text/plain", GzipHandler.GZIP + ";q=0"},
                {"test_quotes.txt", "text/plain", GzipHandler.GZIP + "; q =    0 "}
            }).stream().map(Arguments::of);
    }

    public WorkDir testingdir;

    @ParameterizedTest
    @MethodSource("data")
    public void testNotGzipHandleredDefaultAlreadyCompressed(String alreadyCompressedFilename, String expectedContentType, String compressionType) throws Exception
    {
        GzipTester tester = new GzipTester(testingdir.getEmptyPathDir(), compressionType);

        copyTestFileToServer(alreadyCompressedFilename);

        tester.setContentServlet(TestStaticMimeTypeServlet.class);

        try
        {
            tester.start();
            tester.assertIsResponseNotGziped(alreadyCompressedFilename, alreadyCompressedFilename + ".sha1", expectedContentType);
        }
        finally
        {
            tester.stop();
        }
    }

    private void copyTestFileToServer(String testFilename) throws IOException
    {
        File testFile = MavenTestingUtils.getTestResourceFile(testFilename);
        File outFile = testingdir.getPathFile(testFilename).toFile();
        IO.copy(testFile, outFile);
    }
}
