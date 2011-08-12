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
                { "test_quotes.gz", "application/gzip" },
                { "test_quotes.bz2", "application/bzip2" },
                { "test_quotes.zip", "application/zip" },
                { "test_quotes.rar", "application/octet-stream" },
                // Some images (common first)
                { "jetty_logo.png", "image/png" },
                { "jetty_logo.gif", "image/gif" },
                { "jetty_logo.jpeg", "image/jpeg" },
                { "jetty_logo.jpg", "image/jpeg" },
                // Lesser encountered images (usually found being requested from non-browser clients)
                { "jetty_logo.bmp", "image/bmp" },
                { "jetty_logo.tga", "application/tga" },
                { "jetty_logo.tif", "image/tiff" },
                { "jetty_logo.tiff", "image/tiff" },
                { "jetty_logo.xcf", "image/xcf" },
                { "jetty_logo.jp2", "image/jpeg2000" } });
    }

    @Rule
    public TestingDir testingdir = new TestingDir();

    private String alreadyCompressedFilename;
    private String expectedContentType;

    public GzipFilterDefaultNoRecompressTest(String testFilename, String expectedContentType)
    {
        this.alreadyCompressedFilename = testFilename;
        this.expectedContentType = expectedContentType;
    }

    @Test
    public void testNotGzipFiltered_Default_AlreadyCompressed() throws Exception
    {
        GzipTester tester = new GzipTester(testingdir);

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
