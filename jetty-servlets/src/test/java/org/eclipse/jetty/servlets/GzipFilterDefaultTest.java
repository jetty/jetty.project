package org.eclipse.jetty.servlets;

import org.eclipse.jetty.http.gzip.GzipResponseWrapper;
import org.eclipse.jetty.servlet.DefaultServlet;
import org.eclipse.jetty.servlet.FilterHolder;
import org.eclipse.jetty.servlets.gzip.GzipTester;
import org.eclipse.jetty.toolchain.test.TestingDir;
import org.junit.Rule;
import org.junit.Test;

/**
 * Test the GzipFilter support built into the {@link DefaultServlet}
 */
public class GzipFilterDefaultTest
{
    @Rule
    public TestingDir testingdir = new TestingDir();

    @Test
    public void testIsGzipCompressedTiny() throws Exception
    {
        GzipTester tester = new GzipTester(testingdir);

        // Test content that is smaller than the buffer.
        int filesize = GzipResponseWrapper.DEFAULT_BUFFER_SIZE / 4;
        tester.prepareServerFile("file.txt",filesize);
        
        FilterHolder holder = tester.setContentServlet(org.eclipse.jetty.servlet.DefaultServlet.class);
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
    
    @Test
    public void testIsGzipCompressedLarge() throws Exception
    {
        GzipTester tester = new GzipTester(testingdir);

        // Test content that is smaller than the buffer.
        int filesize = GzipResponseWrapper.DEFAULT_BUFFER_SIZE * 4;
        tester.prepareServerFile("file.txt",filesize);
        
        FilterHolder holder = tester.setContentServlet(org.eclipse.jetty.servlet.DefaultServlet.class);
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
    
    @Test
    public void testIsNotGzipCompressed() throws Exception
    {
        GzipTester tester = new GzipTester(testingdir);

        // Test content that is smaller than the buffer.
        int filesize = GzipResponseWrapper.DEFAULT_BUFFER_SIZE * 4;
        tester.prepareServerFile("file.mp3",filesize);
        
        FilterHolder holder = tester.setContentServlet(org.eclipse.jetty.servlet.DefaultServlet.class);
        holder.setInitParameter("mimeTypes","text/plain");

        try
        {
            tester.start();
            tester.assertIsResponseNotGzipCompressed("file.mp3", filesize);
        }
        finally
        {
            tester.stop();
        }
    }
}
