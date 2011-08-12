package org.eclipse.jetty.servlets;

import java.util.Arrays;
import java.util.List;

import javax.servlet.Servlet;

import org.eclipse.jetty.http.gzip.GzipResponseWrapper;
import org.eclipse.jetty.servlet.FilterHolder;
import org.eclipse.jetty.servlets.gzip.GzipTester;
import org.eclipse.jetty.servlets.gzip.TestServletLengthStreamTypeWrite;
import org.eclipse.jetty.servlets.gzip.TestServletLengthTypeStreamWrite;
import org.eclipse.jetty.servlets.gzip.TestServletStreamLengthTypeWrite;
import org.eclipse.jetty.servlets.gzip.TestServletStreamTypeLengthWrite;
import org.eclipse.jetty.servlets.gzip.TestServletTypeLengthStreamWrite;
import org.eclipse.jetty.servlets.gzip.TestServletTypeStreamLengthWrite;
import org.eclipse.jetty.toolchain.test.TestingDir;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

/**
 * Test the GzipFilter support for Content-Length setting variations.
 * 
 * @see <a href="Eclipse Bug 354014">http://bugs.eclipse.org/354014</a>
 */
@RunWith(Parameterized.class)
public class GzipFilterContentLengthTest
{
    /**
     * These are the junit parameters for running this test.
     * <p>
     * We have 4 test servlets, that arrange the content-length/content-type/get stream in different orders so as to
     * simulate the real world scenario that caused the bug in <a
     * href="Eclipse Bug 354014">http://bugs.eclipse.org/354014</a>
     * <p>
     * This test case will be run with each entry in the array below as setup parameters for the test case.
     * 
     * @return the junit parameters
     */
    @Parameters
    public static List<Object[]> data()
    {
        return Arrays.asList(new Object[][]
        {
        { TestServletLengthStreamTypeWrite.class },
        { TestServletLengthTypeStreamWrite.class },
        { TestServletStreamLengthTypeWrite.class },
        { TestServletStreamTypeLengthWrite.class },
        { TestServletTypeLengthStreamWrite.class },
        { TestServletTypeStreamLengthWrite.class } });
    }

    private static final int LARGE = GzipResponseWrapper.DEFAULT_BUFFER_SIZE * 8;
    private static final int MEDIUM = GzipResponseWrapper.DEFAULT_BUFFER_SIZE;
    private static final int SMALL = GzipResponseWrapper.DEFAULT_BUFFER_SIZE / 4;

    @Rule
    public TestingDir testingdir = new TestingDir();

    private Class<? extends Servlet> testServlet;

    public GzipFilterContentLengthTest(Class<? extends Servlet> testServlet)
    {
        this.testServlet = testServlet;
    }

    private void assertIsGzipCompressed(Class<? extends Servlet> servletClass, int filesize) throws Exception
    {
        GzipTester tester = new GzipTester(testingdir);

        // Test content that is smaller than the buffer.
        tester.prepareServerFile("file.txt",filesize);

        FilterHolder holder = tester.setContentServlet(servletClass);
        holder.setInitParameter("mimeTypes","text/plain");

        try
        {
            tester.start();
            tester.assertIsResponseGzipCompressed("file.txt");
        }
        finally
        {
            tester.stop();
        }
    }

    private void assertIsNotGzipCompressed(Class<? extends Servlet> servletClass, int filesize) throws Exception
    {
        GzipTester tester = new GzipTester(testingdir);

        // Test content that is smaller than the buffer.
        tester.prepareServerFile("file.mp3",filesize);

        FilterHolder holder = tester.setContentServlet(org.eclipse.jetty.servlet.DefaultServlet.class);
        holder.setInitParameter("mimeTypes","text/plain");

        try
        {
            tester.start();
            tester.assertIsResponseNotGzipCompressed("file.mp3",filesize);
        }
        finally
        {
            tester.stop();
        }
    }

    @Test
    public void testIsGzipCompressedTiny() throws Exception
    {
        assertIsGzipCompressed(testServlet,SMALL);
    }

    /**
     * Tests for Length>Type>Stream>Write problems encountered in GzipFilter
     * 
     * @see <a href="Eclipse Bug 354014">http://bugs.eclipse.org/354014</a>
     */
    @Test
    public void testIsGzipCompressedMedium() throws Exception
    {
        assertIsGzipCompressed(testServlet,MEDIUM);
    }

    /**
     * Tests for Length>Type>Stream>Write problems encountered in GzipFilter
     * 
     * @see <a href="Eclipse Bug 354014">http://bugs.eclipse.org/354014</a>
     */
    @Test
    public void testIsGzipCompressedLarge() throws Exception
    {
        assertIsGzipCompressed(testServlet,LARGE);
    }

    /**
     * Tests for Length>Type>Stream>Write problems encountered in GzipFilter
     * 
     * @see <a href="Eclipse Bug 354014">http://bugs.eclipse.org/354014</a>
     */
    @Test
    public void testIsNotGzipCompressed() throws Exception
    {
        assertIsNotGzipCompressed(TestServletLengthTypeStreamWrite.class,LARGE);
    }
}
