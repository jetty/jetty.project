package org.eclipse.jetty.servlets;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;

import org.eclipse.jetty.servlet.DefaultServlet;
import org.eclipse.jetty.servlet.FilterHolder;
import org.eclipse.jetty.servlets.gzip.GzipTester;
import org.eclipse.jetty.toolchain.test.IO;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.toolchain.test.TestingDir;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

/**
 * Tests {@link GzipFilter} in combination with {@link DefaultServlet} for 
 * ability to configure {@link GzipFilter} to ignore recompress situations
 * from upstream.
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
                { "test_quotes.gz" },
                { "test_quotes.bz2" },
                { "test_quotes.zip" },
                { "test_quotes.rar" },
                // Some images (common first)
                { "jetty_logo.png" },
                { "jetty_logo.gif" },
                { "jetty_logo.jpeg" },
                { "jetty_logo.jpg" },
                // Lesser encountered images (usually found being requested from non-browser clients)
                { "jetty_logo.bmp" },
                { "jetty_logo.tga" },
                { "jetty_logo.tif" },
                { "jetty_logo.tiff" },
                { "jetty_logo.xcf" },
                { "jetty_logo.jp2" } });
    }

    @Rule
    public TestingDir testingdir = new TestingDir();
    
    private String alreadyCompressedFilename;
    
    public GzipFilterDefaultNoRecompressTest(String testFilename) {
        this.alreadyCompressedFilename = testFilename;
    }

    @Test
    @Ignore("Cannot find a configuration that would allow this to pass")
    public void testNotGzipFiltered_Default_AlreadyCompressed() throws Exception
    {
        GzipTester tester = new GzipTester(testingdir);

        copyTestFileToServer(alreadyCompressedFilename);

        // Using DefaultServlet, with default GzipFilter setup
        FilterHolder holder = tester.setContentServlet(DefaultServlet.class);
        // TODO: find a configuration of the GzipFilter to allow
        //       each of these test cases to pass.
        
        StringBuilder mimeTypes = new StringBuilder();
        mimeTypes.append("images/png");
        mimeTypes.append(",images/jpeg");
        mimeTypes.append(",images/gif");
        mimeTypes.append(",images/jp2");
        
        holder.setInitParameter("mimeTypes", mimeTypes.toString());

        try
        {
            tester.start();
            tester.assertIsResponseNotGzipFiltered(alreadyCompressedFilename,
                    alreadyCompressedFilename + ".sha1");
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
