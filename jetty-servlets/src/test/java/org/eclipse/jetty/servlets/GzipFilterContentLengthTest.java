package org.eclipse.jetty.servlets;

import java.io.File;
import java.util.Arrays;
import java.util.List;
import javax.servlet.Servlet;

import org.eclipse.jetty.http.HttpStatus;
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
     * In addition to Jetty's DefaultServlet we have multiple test
     * servlets that arrange content-length/content-type/get stream
     * in different order so as to simulate the real world scenario
     * that caused the bug in Eclipse <a href="Bug 354014">http://bugs.eclipse.org/354014</a>
     * <p>
     * This test case will be run with each of the entries in
     * the array below as setup parameters for the test case.
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
    private static final int TINY = GzipResponseWrapper.DEFAULT_MIN_GZIP_SIZE / 2;

    @Rule
    public TestingDir testingdir = new TestingDir();

    private Class<? extends Servlet> testServlet;

    public GzipFilterContentLengthTest(Class<? extends Servlet> testServlet)
    {
        this.testServlet = testServlet;
    }

    private void assertIsGzipCompressed(String filename, int filesize) throws Exception
    {
        GzipTester tester = new GzipTester(testingdir);

        File testfile = tester.prepareServerFile(testServlet.getSimpleName() + "-" + filename,filesize);

        FilterHolder holder = tester.setContentServlet(testServlet);
        holder.setInitParameter("mimeTypes","text/plain");

        try
        {
            tester.start();
            tester.assertIsResponseGzipCompressed(testfile.getName());
        }
        finally
        {
            tester.stop();
        }
    }

    private void assertIsNotGzipCompressed(String filename, int filesize) throws Exception
    {
        GzipTester tester = new GzipTester(testingdir);

        File testfile = tester.prepareServerFile(testServlet.getSimpleName() + "-" + filename,filesize);

        FilterHolder holder = tester.setContentServlet(testServlet);
        holder.setInitParameter("mimeTypes","text/plain");

        try
        {
            tester.start();
            tester.assertIsResponseNotGzipCompressed(testfile.getName(),filesize,HttpStatus.OK_200);
        }
        finally
        {
            tester.stop();
        }
    }

    /**
     * Tests gzip compression of a small size file
     */
    @Test
    public void testIsGzipCompressedSmall() throws Exception
    {
        assertIsGzipCompressed("file-small.txt",SMALL);
    }

    /**
     * Tests gzip compression of a medium size file
     */
    @Test
    public void testIsGzipCompressedMedium() throws Exception
    {
        assertIsGzipCompressed("file-med.txt",MEDIUM);
    }

    /**
     * Tests gzip compression of a large size file
     */
    @Test
    public void testIsGzipCompressedLarge() throws Exception
    {
        assertIsGzipCompressed("file-large.txt",LARGE);
    }

    /**
     * Tests for problems with Content-Length header on small size files
     * that are not being compressed encountered when using GzipFilter
     *
     * @see <a href="Eclipse Bug 354014">http://bugs.eclipse.org/354014</a>
     */
    @Test
    public void testIsNotGzipCompressedTiny() throws Exception
    {
        assertIsNotGzipCompressed("file-tiny.txt",TINY);
    }

    /**
     * Tests for problems with Content-Length header on small size files
     * that are not being compressed encountered when using GzipFilter
     *
     * @see <a href="Eclipse Bug 354014">http://bugs.eclipse.org/354014</a>
     */
    @Test
    public void testIsNotGzipCompressedSmall() throws Exception
    {
        assertIsNotGzipCompressed("file-small.mp3",SMALL);
    }

    /**
     * Tests for problems with Content-Length header on medium size files
     * that are not being compressed encountered when using GzipFilter
     *
     * @see <a href="Eclipse Bug 354014">http://bugs.eclipse.org/354014</a>
     */
    @Test
    public void testIsNotGzipCompressedMedium() throws Exception
    {
        assertIsNotGzipCompressed("file-medium.mp3",MEDIUM);
    }

    /**
     * Tests for problems with Content-Length header on large size files
     * that were not being compressed encountered when using GzipFilter
     *
     * @see <a href="Eclipse Bug 354014">http://bugs.eclipse.org/354014</a>
     */
    @Test
    public void testIsNotGzipCompressedLarge() throws Exception
    {
        assertIsNotGzipCompressed("file-large.mp3",LARGE);
    }
}
