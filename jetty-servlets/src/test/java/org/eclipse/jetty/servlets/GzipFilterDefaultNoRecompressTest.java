package org.eclipse.jetty.servlets;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;

import org.eclipse.jetty.http.gzip.CompressionType;
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
                { "test_quotes.gz", "application/gzip", CompressionType.GZIP },
                { "test_quotes.bz2", "application/bzip2", CompressionType.GZIP },
                { "test_quotes.zip", "application/zip", CompressionType.GZIP },
                { "test_quotes.rar", "application/octet-stream", CompressionType.GZIP },
                // Some images (common first)
                { "jetty_logo.png", "image/png", CompressionType.GZIP },
                { "jetty_logo.gif", "image/gif", CompressionType.GZIP },
                { "jetty_logo.jpeg", "image/jpeg", CompressionType.GZIP },
                { "jetty_logo.jpg", "image/jpeg", CompressionType.GZIP },
                // Lesser encountered images (usually found being requested from non-browser clients)
                { "jetty_logo.bmp", "image/bmp", CompressionType.GZIP },
                { "jetty_logo.tga", "application/tga", CompressionType.GZIP },
                { "jetty_logo.tif", "image/tiff", CompressionType.GZIP },
                { "jetty_logo.tiff", "image/tiff", CompressionType.GZIP },
                { "jetty_logo.xcf", "image/xcf", CompressionType.GZIP },
                { "jetty_logo.jp2", "image/jpeg2000", CompressionType.GZIP },

                // Same tests again for deflate
                // Some already compressed files
                { "test_quotes.gz", "application/gzip", CompressionType.DEFLATE },
                { "test_quotes.bz2", "application/bzip2", CompressionType.DEFLATE },
                { "test_quotes.zip", "application/zip", CompressionType.DEFLATE },
                { "test_quotes.rar", "application/octet-stream", CompressionType.DEFLATE },
                // Some images (common first)
                { "jetty_logo.png", "image/png", CompressionType.DEFLATE },
                { "jetty_logo.gif", "image/gif", CompressionType.DEFLATE },
                { "jetty_logo.jpeg", "image/jpeg", CompressionType.DEFLATE },
                { "jetty_logo.jpg", "image/jpeg", CompressionType.DEFLATE },
                // Lesser encountered images (usually found being requested from non-browser clients)
                { "jetty_logo.bmp", "image/bmp", CompressionType.DEFLATE },
                { "jetty_logo.tga", "application/tga", CompressionType.DEFLATE },
                { "jetty_logo.tif", "image/tiff", CompressionType.DEFLATE },
                { "jetty_logo.tiff", "image/tiff", CompressionType.DEFLATE },
                { "jetty_logo.xcf", "image/xcf", CompressionType.DEFLATE },
                { "jetty_logo.jp2", "image/jpeg2000", CompressionType.DEFLATE } });
    }

    @Rule
    public TestingDir testingdir = new TestingDir();

    private String alreadyCompressedFilename;
    private String expectedContentType;
    private CompressionType compressionType;

    public GzipFilterDefaultNoRecompressTest(String testFilename, String expectedContentType, CompressionType compressionType)
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
