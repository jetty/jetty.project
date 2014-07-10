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

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

import javax.servlet.Filter;
import javax.servlet.ServletException;
import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.servlet.DefaultServlet;
import org.eclipse.jetty.servlet.FilterHolder;
import org.eclipse.jetty.servlets.gzip.GzipTester;
import org.eclipse.jetty.toolchain.test.TestingDir;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

/**
 * Test the GzipFilter support built into the {@link DefaultServlet}
 */
@RunWith(Parameterized.class)
public class GzipFilterDefaultTest
{
    @Parameters
    public static List<Object[]> data()
    {
        return Arrays.asList(new Object[][]
        { 
            { AsyncGzipFilter.class, GzipFilter.GZIP },
            { GzipFilter.class, GzipFilter.GZIP },
            { GzipFilter.class, GzipFilter.DEFLATE },
        });
    }

    private Class<? extends Filter> testFilter;
    private String compressionType;

    public GzipFilterDefaultTest(Class<? extends Filter> testFilter, String compressionType)
    {
        this.testFilter=testFilter;
        this.compressionType = compressionType;
    }

    public static class HttpStatusServlet extends HttpServlet
    {
        private int _status = 204;

        public HttpStatusServlet()
        {
            super();
        }

        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException
        {
            resp.setStatus(_status);
            resp.setHeader("ETag","W/\"204\"");
        }

    }

    public static class HttpErrorServlet extends HttpServlet
    {
        private int _status = 400;

        public HttpErrorServlet()
        {
            super();
        }

        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException
        {
            resp.getOutputStream().write("error message".getBytes());
            resp.setStatus(_status);
        }
    }
    

    public static class HttpContentTypeWithEncoding extends HttpServlet
    {
        public static final String COMPRESSED_CONTENT = "<html><head></head><body><h1>COMPRESSABLE CONTENT</h1>"+
        "This content must be longer than the default min gzip length, which is 256 bytes. "+
        "The moon is blue to a fish in love. How now brown cow. The quick brown fox jumped over the lazy dog. A woman needs a man like a fish needs a bicycle!"+
        "</body></html>";
        
        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp)
        throws ServletException, IOException
        {
            resp.setContentType("text/plain;charset=UTF8");
            resp.setStatus(200);
            ServletOutputStream out = resp.getOutputStream();
            out.print(COMPRESSED_CONTENT);
        }

    }

    @Rule
    public TestingDir testingdir = new TestingDir();

    

    @Test
    public void testIsGzipByMethod() throws Exception
    {
        GzipTester tester = new GzipTester(testingdir, compressionType);
        tester.setGzipFilterClass(testFilter);

        int filesize = tester.getOutputBufferSize() * 2;
        tester.prepareServerFile("file.txt",filesize);
        
        FilterHolder holder = tester.setContentServlet(GetServlet.class);
        holder.setInitParameter("mimeTypes","text/plain");
        holder.setInitParameter("methods","POST, WIBBLE");
                
        try
        {
            tester.start();
            tester.assertIsResponseGzipCompressed("POST","file.txt");
            tester.assertIsResponseGzipCompressed("WIBBLE","file.txt");
            tester.assertIsResponseNotGzipCompressed("GET","file.txt",filesize,200);
        }
        finally
        {
            tester.stop();
        }
    }
    
    public static class GetServlet extends DefaultServlet
    {
        public GetServlet()
        {    
            super();
        }
        
        @Override
        public void service(HttpServletRequest req, HttpServletResponse resp) throws IOException,ServletException
        {
            String uri=req.getRequestURI();
            if (uri.endsWith(".deferred"))
            {
                // System.err.println("type for "+uri.substring(0,uri.length()-9)+" is "+getServletContext().getMimeType(uri.substring(0,uri.length()-9)));
                resp.setContentType(getServletContext().getMimeType(uri.substring(0,uri.length()-9)));
            }
            
            doGet(req,resp);
        }
    }
    
   
    
    @Test
    public void testIsGzipCompressedEmpty() throws Exception
    {
        GzipTester tester = new GzipTester(testingdir, compressionType);
        tester.setGzipFilterClass(testFilter);

        tester.prepareServerFile("empty.txt",0);
        
        FilterHolder holder = tester.setContentServlet(org.eclipse.jetty.servlet.DefaultServlet.class);
        holder.setInitParameter("mimeTypes","text/plain");

        try
        {
            tester.start();
            HttpTester.Response http = tester.assertIsResponseNotGzipCompressed("GET","empty.txt",0,200);
        }
        finally
        {
            tester.stop();
        }
    }
    
    @Test
    public void testIsGzipCompressedTiny() throws Exception
    {
        GzipTester tester = new GzipTester(testingdir, compressionType);
        tester.setGzipFilterClass(testFilter);

        int filesize = tester.getOutputBufferSize() / 4;
        tester.prepareServerFile("file.txt",filesize);

        FilterHolder holder = tester.setContentServlet(org.eclipse.jetty.servlet.DefaultServlet.class);
        holder.setInitParameter("mimeTypes","text/plain");

        try
        {
            tester.start();
            HttpTester.Response http = tester.assertIsResponseGzipCompressed("GET","file.txt");
            Assert.assertEquals("Accept-Encoding",http.get("Vary"));
        }
        finally
        {
            tester.stop();
        }
    }
    
    @Test
    public void testIsGzipCompressedLarge() throws Exception
    {
        GzipTester tester = new GzipTester(testingdir, compressionType);
        tester.setGzipFilterClass(testFilter);

        int filesize = tester.getOutputBufferSize() * 4;
        tester.prepareServerFile("file.txt",filesize);

        FilterHolder holder = tester.setContentServlet(org.eclipse.jetty.servlet.DefaultServlet.class);
        holder.setInitParameter("mimeTypes","text/plain");

        try
        {
            tester.start();
            HttpTester.Response http = tester.assertIsResponseGzipCompressed("GET","file.txt");
            Assert.assertEquals("Accept-Encoding",http.get("Vary"));
        }
        finally
        {
            tester.stop();
        }
    }
    

    @Test
    public void testGzipedIfModified() throws Exception
    {
        GzipTester tester = new GzipTester(testingdir, compressionType);
        tester.setGzipFilterClass(testFilter);

        int filesize = tester.getOutputBufferSize() * 4;
        tester.prepareServerFile("file.txt",filesize);
        
        FilterHolder holder = tester.setContentServlet(org.eclipse.jetty.servlet.DefaultServlet.class);
        holder.setInitParameter("mimeTypes","text/plain");

        try
        {
            tester.start();
            HttpTester.Response http = tester.assertIsResponseGzipCompressed("GET","file.txt",System.currentTimeMillis()-4000);
            Assert.assertEquals("Accept-Encoding",http.get("Vary"));
        }
        finally
        {
            tester.stop();
        }
    }
    
    @Test
    public void testGzippedIfSVG() throws Exception
    {
        GzipTester tester = new GzipTester(testingdir, compressionType);
        tester.setGzipFilterClass(testFilter);
        tester.copyTestServerFile("test.svg");
        FilterHolder holder = tester.setContentServlet(org.eclipse.jetty.servlet.DefaultServlet.class);
        try
        {
            tester.start();
            HttpTester.Response http = tester.assertIsResponseGzipCompressed("GET","test.svg",System.currentTimeMillis()-4000);
            Assert.assertEquals("Accept-Encoding",http.get("Vary"));
        }
        finally
        {
            tester.stop();
        }
    }

    @Test
    public void testNotGzipedIfNotModified() throws Exception
    {
        GzipTester tester = new GzipTester(testingdir, compressionType);
        tester.setGzipFilterClass(testFilter);

        int filesize = tester.getOutputBufferSize() * 4;
        tester.prepareServerFile("file.txt",filesize);
        
        FilterHolder holder = tester.setContentServlet(org.eclipse.jetty.servlet.DefaultServlet.class);
        holder.setInitParameter("mimeTypes","text/plain");
        holder.setInitParameter("etags","true");

        try
        {
            tester.start();
            tester.assertIsResponseNotModified("GET","file.txt",System.currentTimeMillis()+4000);
        }
        finally
        {
            tester.stop();
        }
    }
    

    @Test
    public void testIsNotGzipCompressedWithZeroQ() throws Exception
    {
        GzipTester tester = new GzipTester(testingdir, compressionType+"; q=0");
        tester.setGzipFilterClass(testFilter);
        
        int filesize = tester.getOutputBufferSize() / 4;
        tester.prepareServerFile("file.txt",filesize);
        
        FilterHolder holder = tester.setContentServlet(org.eclipse.jetty.servlet.DefaultServlet.class);
        holder.setInitParameter("mimeTypes","text/plain");

        try
        {
            tester.start();
            HttpTester.Response http = tester.assertIsResponseNotGzipCompressed("GET","file.txt", filesize, HttpStatus.OK_200);
            Assert.assertEquals("Accept-Encoding",http.get("Vary"));
        }
        finally
        {
            tester.stop();
        }
    }

    @Test
    public void testIsGzipCompressedWithQ() throws Exception
    {
        GzipTester tester = new GzipTester(testingdir, compressionType,"something;q=0.1,"+compressionType+";q=0.5");
        tester.setGzipFilterClass(testFilter);
        
        int filesize = tester.getOutputBufferSize() / 4;
        tester.prepareServerFile("file.txt",filesize);
        
        FilterHolder holder = tester.setContentServlet(org.eclipse.jetty.servlet.DefaultServlet.class);
        holder.setInitParameter("mimeTypes","text/plain");

        try
        {
            tester.start();
            HttpTester.Response http = tester.assertIsResponseGzipCompressed("GET","file.txt");
            Assert.assertEquals("Accept-Encoding",http.get("Vary"));
        }
        finally
        {
            tester.stop();
        }
    }
    
    @Test
    public void testIsNotGzipCompressedByContentType() throws Exception
    {
        GzipTester tester = new GzipTester(testingdir, compressionType);
        tester.setGzipFilterClass(testFilter);

        int filesize = tester.getOutputBufferSize() * 4;
        tester.prepareServerFile("file.mp3",filesize);

        FilterHolder holder = tester.setContentServlet(org.eclipse.jetty.servlet.DefaultServlet.class);
        holder.setInitParameter("mimeTypes","text/plain");

        try
        {
            tester.start();
            HttpTester.Response http = tester.assertIsResponseNotGzipCompressed("GET","file.mp3", filesize, HttpStatus.OK_200);
            Assert.assertNull(http.get("Vary"));
        }
        finally
        {
            tester.stop();
        }
    }
    
    
    @Test
    public void testIsNotGzipCompressedByExcludedContentType() throws Exception
    {
        GzipTester tester = new GzipTester(testingdir, compressionType);
        tester.setGzipFilterClass(testFilter);

        int filesize = tester.getOutputBufferSize() * 4;
        tester.prepareServerFile("test_quotes.txt", filesize);
    

        FilterHolder holder = tester.setContentServlet(org.eclipse.jetty.servlet.DefaultServlet.class);
        holder.setInitParameter("excludedMimeTypes","text/plain");

        try
        {
            tester.start();
            HttpTester.Response http = tester.assertIsResponseNotGzipCompressed("GET","test_quotes.txt", filesize, HttpStatus.OK_200);
            Assert.assertNull(http.get("Vary"));
        }
        finally
        {
            tester.stop();
        }
    }
    
    
    @Test
    public void testIsNotGzipCompressedByExcludedContentTypeWithCharset() throws Exception
    {
        GzipTester tester = new GzipTester(testingdir, compressionType);
        tester.setGzipFilterClass(testFilter);

        int filesize = tester.getOutputBufferSize() * 4;
        tester.prepareServerFile("test_quotes.txt", filesize);
        tester.addMimeType("txt","text/plain;charset=UTF-8");

        FilterHolder holder = tester.setContentServlet(org.eclipse.jetty.servlet.DefaultServlet.class);
        holder.setInitParameter("excludedMimeTypes","text/plain");

        try
        {
            tester.start();
            HttpTester.Response http = tester.assertIsResponseNotGzipCompressed("GET","test_quotes.txt", filesize, HttpStatus.OK_200);
            Assert.assertNull(http.get("Vary"));
        }
        finally
        {
            tester.stop();
        }
    }
    
    
    
    @Test
    public void testGzipCompressedByContentTypeWithEncoding() throws Exception
    { 
        GzipTester tester = new GzipTester(testingdir, compressionType);
        tester.setGzipFilterClass(testFilter);
        FilterHolder holder = tester.setContentServlet(HttpContentTypeWithEncoding.class);
        holder.setInitParameter("mimeTypes","text/plain");
        try
        {
            tester.start();
            HttpTester.Response http = tester.assertNonStaticContentIsResponseGzipCompressed("GET","xxx", HttpContentTypeWithEncoding.COMPRESSED_CONTENT);
            Assert.assertEquals("Accept-Encoding",http.get("Vary"));
        }
        finally
        {
            tester.stop();
        }
    }
    
    
    @Test
    public void testIsNotGzipCompressedByDeferredContentType() throws Exception
    {
        GzipTester tester = new GzipTester(testingdir, compressionType);
        tester.setGzipFilterClass(testFilter);

        int filesize = tester.getOutputBufferSize() * 4;
        tester.prepareServerFile("file.mp3.deferred",filesize);
        
        FilterHolder holder = tester.setContentServlet(GetServlet.class);
        holder.setInitParameter("mimeTypes","text/plain");

        try
        {
            tester.start();
            HttpTester.Response http = tester.assertIsResponseNotGzipCompressed("GET","file.mp3.deferred", filesize, HttpStatus.OK_200);
            Assert.assertNull(http.get("Vary"));
        }
        finally
        {
            tester.stop();
        }
    }
    
    @Test
    public void testIsNotGzipCompressedHttpStatus() throws Exception
    {
        GzipTester tester = new GzipTester(testingdir, compressionType);
        tester.setGzipFilterClass(testFilter);

        // Test error code 204
        FilterHolder holder = tester.setContentServlet(HttpStatusServlet.class);
        holder.setInitParameter("mimeTypes","text/plain");

        try
        {
            tester.start();
            tester.assertIsResponseNotGzipCompressed("GET",-1, 204);
        }
        finally
        {
            tester.stop();
        }

    }

    @Test
    public void testIsNotGzipCompressedHttpBadRequestStatus() throws Exception
    {
        GzipTester tester = new GzipTester(testingdir, compressionType);
        tester.setGzipFilterClass(testFilter);

        // Test error code 400
        FilterHolder holder = tester.setContentServlet(HttpErrorServlet.class);
        holder.setInitParameter("mimeTypes","text/plain");

        try
        {
            tester.start();
            tester.assertIsResponseNotGzipCompressedAndEqualToExpectedString("GET","error message", -1, 400);
        }
        finally
        {
            tester.stop();
        }

    }

    @Test
    public void testUserAgentExclusion() throws Exception
    {
        GzipTester tester = new GzipTester(testingdir,compressionType);
        tester.setGzipFilterClass(testFilter);

        FilterHolder holder = tester.setContentServlet(DefaultServlet.class);
        holder.setInitParameter("excludedAgents","bar, foo");
        tester.setUserAgent("foo");

        int filesize = tester.getOutputBufferSize() * 4;
        tester.prepareServerFile("file.txt",filesize);

        try
        {
            tester.start();
            tester.assertIsResponseNotGzipCompressed("GET","file.txt",filesize,HttpStatus.OK_200);
        }
        finally
        {
            tester.stop();
        }
    }

    @Test
    public void testUserAgentExclusionByExcludedAgentPatterns() throws Exception
    {
        GzipTester tester = new GzipTester(testingdir,compressionType);
        tester.setGzipFilterClass(testFilter);

        FilterHolder holder = tester.setContentServlet(DefaultServlet.class);
        holder.setInitParameter("excludedAgents","bar");
        holder.setInitParameter("excludeAgentPatterns","fo.*");
        tester.setUserAgent("foo");

        int filesize = tester.getOutputBufferSize() * 4;
        tester.prepareServerFile("file.txt",filesize);

        try
        {
            tester.start();
            tester.assertIsResponseNotGzipCompressed("GET","file.txt",filesize,HttpStatus.OK_200);
        }
        finally
        {
            tester.stop();
        }
    }

    @Test
    public void testExcludePaths() throws Exception
    {
        GzipTester tester = new GzipTester(testingdir,compressionType);
        tester.setGzipFilterClass(testFilter);

        FilterHolder holder = tester.setContentServlet(DefaultServlet.class);
        holder.setInitParameter("excludePaths","/bar/, /context/");

        int filesize = tester.getOutputBufferSize() * 4;
        tester.prepareServerFile("file.txt",filesize);

        try
        {
            tester.start();
            tester.assertIsResponseNotGzipCompressed("GET","file.txt",filesize,HttpStatus.OK_200);
        }
        finally
        {
            tester.stop();
        }
    }

    @Test
    public void testExcludePathPatterns() throws Exception
    {
        GzipTester tester = new GzipTester(testingdir,compressionType);
        tester.setGzipFilterClass(testFilter);

        FilterHolder holder = tester.setContentServlet(DefaultServlet.class);
        holder.setInitParameter("excludePathPatterns","/cont.*");

        int filesize = tester.getOutputBufferSize() * 4;
        tester.prepareServerFile("file.txt",filesize);

        try
        {
            tester.start();
            tester.assertIsResponseNotGzipCompressed("GET","file.txt",filesize,HttpStatus.OK_200);
        }
        finally
        {
            tester.stop();
        }
    }
    
    
    @Test
    public void testIsNotGzipCompressedSVGZ() throws Exception
    {
        GzipTester tester = new GzipTester(testingdir,compressionType);
        tester.setGzipFilterClass(testFilter);

        FilterHolder holder = tester.setContentServlet(DefaultServlet.class);
        tester.copyTestServerFile("test.svgz");
        try
        {
            tester.start();
            tester.assertIsResponseNotGzipFiltered("test.svgz", "test.svgz.sha1", "image/svg+xml", "gzip");
        }
        finally
        {
            tester.stop();
        }
    }
}
