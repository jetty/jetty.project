package org.eclipse.jetty.servlets;

import java.io.IOException;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.http.HttpStatus;
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
    
    
    public static class HttpStatusServlet extends HttpServlet
    {
        private int _status = 204;
        
        public HttpStatusServlet()
        {
            super();
        }
        
        public void setStatus (int status)
        {
            _status = status;
        }

        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException
        {
            resp.setStatus(_status);
        }
        
    }
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
            tester.assertIsResponseNotGzipCompressed("file.mp3", filesize, HttpStatus.OK_200);
        }
        finally
        {
            tester.stop();
        }
    }
    
    @Test
    public void testIsNotGzipCompressedHttpStatus() throws Exception
    { 
        GzipTester tester = new GzipTester(testingdir);

        // Test error code 204
        FilterHolder holder = tester.setContentServlet(HttpStatusServlet.class);
        holder.setInitParameter("mimeTypes","text/plain");

        try
        {
            tester.start();
            tester.assertIsResponseNotGzipCompressed(null, -1, 204);
        }
        finally
        {
            tester.stop();
        }

    }

    @Test
    public void testUserAgentExclusion() throws Exception
    {
        GzipTester tester = new GzipTester(testingdir);
        
        FilterHolder holder = tester.setContentServlet(DefaultServlet.class);
        holder.setInitParameter("excludedAgents", "foo");
        tester.setUserAgent("foo");
        
        int filesize = GzipResponseWrapper.DEFAULT_BUFFER_SIZE * 4;
        tester.prepareServerFile("file.txt",filesize);
        
        try
        {
            tester.start();
            tester.assertIsResponseNotGzipCompressed("file.txt", filesize, HttpStatus.OK_200);
        } 
        finally
        {
            tester.stop();
        }
    }
}
