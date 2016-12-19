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

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.isEmptyOrNullString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThat;

import java.io.File;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.util.concurrent.TimeUnit;

import javax.servlet.ServletException;
import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.http.CompressedContentFormat;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.servlet.DefaultServlet;
import org.eclipse.jetty.toolchain.test.IO;
import org.eclipse.jetty.toolchain.test.TestingDir;
import org.eclipse.jetty.util.StringUtil;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;

/**
 * Test the GzipHandler support built into the {@link DefaultServlet}
 */
public class GzipDefaultTest
{
    private String compressionType;

    public GzipDefaultTest()
    {
        this.compressionType = GzipHandler.GZIP;
    }

    @SuppressWarnings("serial")
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

    @SuppressWarnings("serial")
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

    @SuppressWarnings("serial")
    public static class HttpContentTypeWithEncoding extends HttpServlet
    {
        public static final String COMPRESSED_CONTENT = "<html><head></head><body><h1>COMPRESSABLE CONTENT</h1>"
                + "This content must be longer than the default min gzip length, which is 256 bytes. "
                + "The moon is blue to a fish in love. How now brown cow. The quick brown fox jumped over the lazy dog. A woman needs a man like a fish needs a bicycle!"
                + "</body></html>";

        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException
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
        GzipTester tester = new GzipTester(testingdir,compressionType);

        // Configure Gzip Handler
        tester.getGzipHandler().setIncludedMethods("POST","WIBBLE", "HEAD");

        // Prepare Server File
        int filesize = tester.getOutputBufferSize() * 2;
        tester.prepareServerFile("file.txt",filesize);

        // Content Servlet
        tester.setContentServlet(GetServlet.class);

        try
        {
            tester.start();
            HttpTester.Response response;

            //These methods have content bodies of the compressed response
            tester.assertIsResponseGzipCompressed("POST","file.txt");
            tester.assertIsResponseGzipCompressed("WIBBLE","file.txt");
            
            //A HEAD request should have similar headers, but no body
            response = tester.executeRequest("HEAD","/context/file.txt",5,TimeUnit.SECONDS);
            assertThat("Response status",response.getStatus(),is(HttpStatus.OK_200));
            assertThat("ETag", response.get("ETag"), containsString(CompressedContentFormat.GZIP._etag));
            assertThat("Content encoding", response.get("Content-Encoding"), containsString("gzip"));
            assertNull("Content length", response.get("Content-Length"));
   
            response = tester.executeRequest("GET","/context/file.txt",5,TimeUnit.SECONDS);

            assertThat("Response status",response.getStatus(),is(HttpStatus.OK_200));
            assertThat("Content-Encoding",response.get("Content-Encoding"),not(containsString(compressionType)));

            String content = tester.readResponse(response);
            assertThat("Response content size",content.length(),is(filesize));
            String expectedContent = IO.readToString(testingdir.getFile("file.txt"));
            assertThat("Response content",content,is(expectedContent));
        }
        finally
        {
            tester.stop();
        }
    }

    @SuppressWarnings("serial")
    public static class GetServlet extends DefaultServlet
    {
        public GetServlet()
        {
            super();
        }

        @Override
        public void service(HttpServletRequest req, HttpServletResponse resp) throws IOException, ServletException
        {
            String uri = req.getRequestURI();
            if (uri.endsWith(".deferred"))
            {
                // System.err.println("type for "+uri.substring(0,uri.length()-9)+" is "+getServletContext().getMimeType(uri.substring(0,uri.length()-9)));
                resp.setContentType(getServletContext().getMimeType(uri.substring(0,uri.length() - 9)));
            }

            doGet(req,resp);
        }
    }

    @Test
    public void testIsGzipCompressedEmpty() throws Exception
    {
        GzipTester tester = new GzipTester(testingdir,compressionType);

        // Configure Gzip Handler
        tester.getGzipHandler().addIncludedMimeTypes("text/plain");

        // Prepare server file
        tester.prepareServerFile("empty.txt",0);

        // Set content servlet
        tester.setContentServlet(DefaultServlet.class);

        try
        {
            tester.start();

            HttpTester.Response response;

            response = tester.executeRequest("GET","/context/empty.txt",5,TimeUnit.SECONDS);

            assertThat("Response status",response.getStatus(),is(HttpStatus.OK_200));
            assertThat("Content-Encoding",response.get("Content-Encoding"),not(containsString(compressionType)));

            String content = tester.readResponse(response);
            assertThat("Response content size",content.length(),is(0));
            String expectedContent = IO.readToString(testingdir.getFile("empty.txt"));
            assertThat("Response content",content,is(expectedContent));
        }
        finally
        {
            tester.stop();
        }
    }

    @Test
    public void testIsGzipCompressedTiny() throws Exception
    {
        GzipTester tester = new GzipTester(testingdir,compressionType);

        int filesize = tester.getOutputBufferSize() / 4;
        tester.prepareServerFile("file.txt",filesize);

        tester.setContentServlet(org.eclipse.jetty.servlet.DefaultServlet.class);

        try
        {
            tester.start();
            HttpTester.Response http = tester.assertIsResponseGzipCompressed("GET","file.txt");
            Assert.assertEquals("Accept-Encoding, User-Agent",http.get("Vary"));
        }
        finally
        {
            tester.stop();
        }
    }

    @Test
    public void testIsGzipCompressedLarge() throws Exception
    {
        GzipTester tester = new GzipTester(testingdir,compressionType);

        int filesize = tester.getOutputBufferSize() * 4;
        tester.prepareServerFile("file.txt",filesize);

        tester.setContentServlet(org.eclipse.jetty.servlet.DefaultServlet.class);
        tester.getGzipHandler().setExcludedAgentPatterns();

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
        GzipTester tester = new GzipTester(testingdir,compressionType);

        int filesize = tester.getOutputBufferSize() * 4;
        tester.prepareServerFile("file.txt",filesize);

        tester.setContentServlet(org.eclipse.jetty.servlet.DefaultServlet.class);

        try
        {
            tester.start();
            HttpTester.Response http = tester.assertIsResponseGzipCompressed("GET","file.txt",System.currentTimeMillis() - 4000);
            Assert.assertEquals("Accept-Encoding, User-Agent",http.get("Vary"));
        }
        finally
        {
            tester.stop();
        }
    }

    @Test
    public void testGzippedIfSVG() throws Exception
    {
        GzipTester tester = new GzipTester(testingdir,compressionType);
        tester.copyTestServerFile("test.svg");
        tester.setContentServlet(org.eclipse.jetty.servlet.DefaultServlet.class);

        tester.getGzipHandler().addIncludedMimeTypes("image/svg+xml");

        try
        {
            tester.start();
            HttpTester.Response http = tester.assertIsResponseGzipCompressed("GET","test.svg",System.currentTimeMillis() - 4000);
            Assert.assertEquals("Accept-Encoding, User-Agent",http.get("Vary"));
        }
        finally
        {
            tester.stop();
        }
    }

    @Test
    public void testNotGzipedIfNotModified() throws Exception
    {
        GzipTester tester = new GzipTester(testingdir,compressionType);

        int filesize = tester.getOutputBufferSize() * 4;
        tester.prepareServerFile("file.txt",filesize);

        tester.setContentServlet(org.eclipse.jetty.servlet.DefaultServlet.class);

        try
        {
            tester.start();
            tester.assertIsResponseNotModified("GET","file.txt",System.currentTimeMillis() + 4000);
        }
        finally
        {
            tester.stop();
        }
    }

    @Test
    public void testIsNotGzipCompressedWithZeroQ() throws Exception
    {
        GzipTester tester = new GzipTester(testingdir,compressionType + "; q=0");

        // Configure Gzip Handler
        tester.getGzipHandler().addIncludedMimeTypes("text/plain");

        // Prepare server file
        int filesize = tester.getOutputBufferSize() / 4;
        tester.prepareServerFile("file.txt",filesize);

        // Add content servlet
        tester.setContentServlet(DefaultServlet.class);

        try
        {
            tester.start();
            HttpTester.Response http = assertIsResponseNotGzipCompressed(tester,"GET","file.txt",filesize,HttpStatus.OK_200);
            assertThat("Response[Vary]",http.get("Vary"),containsString("Accept-Encoding"));
        }
        finally
        {
            tester.stop();
        }
    }

    @Test
    public void testIsGzipCompressedWithQ() throws Exception
    {
        GzipTester tester = new GzipTester(testingdir,compressionType,"something;q=0.1," + compressionType + ";q=0.5");

        int filesize = tester.getOutputBufferSize() / 4;
        tester.prepareServerFile("file.txt",filesize);

        tester.setContentServlet(org.eclipse.jetty.servlet.DefaultServlet.class);
        tester.getGzipHandler().setExcludedAgentPatterns();

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
        GzipTester tester = new GzipTester(testingdir,compressionType);

        // Prepare server file
        int filesize = tester.getOutputBufferSize() * 4;
        tester.prepareServerFile("file.mp3",filesize);

        // Add content servlet
        tester.setContentServlet(DefaultServlet.class);

        try
        {
            tester.start();
            HttpTester.Response http = assertIsResponseNotGzipCompressed(tester,"GET","file.mp3",filesize,HttpStatus.OK_200);
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
        GzipTester tester = new GzipTester(testingdir,compressionType);

        // Configure Gzip Handler
        tester.getGzipHandler().addExcludedMimeTypes("text/plain");

        // Prepare server file
        int filesize = tester.getOutputBufferSize() * 4;
        tester.prepareServerFile("test_quotes.txt",filesize);

        // Add content servlet
        tester.setContentServlet(DefaultServlet.class);

        try
        {
            tester.start();
            HttpTester.Response http = assertIsResponseNotGzipCompressed(tester,"GET","test_quotes.txt",filesize,HttpStatus.OK_200);
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
        GzipTester tester = new GzipTester(testingdir,compressionType);

        // Configure Gzip Handler
        tester.getGzipHandler().addExcludedMimeTypes("text/plain");

        // Prepare server file
        int filesize = tester.getOutputBufferSize() * 4;
        tester.prepareServerFile("test_quotes.txt",filesize);
        tester.addMimeType("txt","text/plain;charset=UTF-8");

        // Add content servlet
        tester.setContentServlet(DefaultServlet.class);

        try
        {
            tester.start();
            HttpTester.Response http = assertIsResponseNotGzipCompressed(tester,"GET","test_quotes.txt",filesize,HttpStatus.OK_200);
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
        GzipTester tester = new GzipTester(testingdir,compressionType);
        tester.setContentServlet(HttpContentTypeWithEncoding.class);
        tester.getGzipHandler().addIncludedMimeTypes("text/plain");
        tester.getGzipHandler().setExcludedAgentPatterns();
        try
        {
            tester.start();
            HttpTester.Response http = tester.assertNonStaticContentIsResponseGzipCompressed("GET","xxx",HttpContentTypeWithEncoding.COMPRESSED_CONTENT);
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
        GzipTester tester = new GzipTester(testingdir,compressionType);

        // Configure Gzip Handler
        tester.getGzipHandler().addIncludedMimeTypes("text/plain");

        // Prepare server file
        int filesize = tester.getOutputBufferSize() * 4;
        tester.prepareServerFile("file.mp3.deferred",filesize);

        // Add content servlet
        tester.setContentServlet(GetServlet.class);

        try
        {
            tester.start();
            HttpTester.Response response = assertIsResponseNotGzipCompressed(tester,"GET","file.mp3.deferred",filesize,HttpStatus.OK_200);
            assertThat("Response[Vary]", response.get("Vary"), isEmptyOrNullString());
        }
        finally
        {
            tester.stop();
        }
    }

    @Test
    public void testIsNotGzipCompressedHttpStatus() throws Exception
    {
        GzipTester tester = new GzipTester(testingdir,compressionType);

        // Configure Gzip Handler
        tester.getGzipHandler().addIncludedMimeTypes("text/plain");

        // Test error code 204
        tester.setContentServlet(HttpStatusServlet.class);

        try
        {
            tester.start();

            HttpTester.Response response = tester.executeRequest("GET","/context/",5,TimeUnit.SECONDS);

            assertThat("Response status",response.getStatus(),is(HttpStatus.NO_CONTENT_204));
            assertThat("Content-Encoding",response.get("Content-Encoding"),not(containsString(compressionType)));
        }
        finally
        {
            tester.stop();
        }

    }

    @Test
    public void testIsNotGzipCompressedHttpBadRequestStatus() throws Exception
    {
        GzipTester tester = new GzipTester(testingdir,compressionType);

        // Configure Gzip Handler
        tester.getGzipHandler().addIncludedMimeTypes("text/plain");

        // Test error code 400
        tester.setContentServlet(HttpErrorServlet.class);

        try
        {
            tester.start();

            HttpTester.Response response = tester.executeRequest("GET","/context/",5,TimeUnit.SECONDS);

            assertThat("Response status",response.getStatus(),is(HttpStatus.BAD_REQUEST_400));
            assertThat("Content-Encoding",response.get("Content-Encoding"),not(containsString(compressionType)));

            String content = tester.readResponse(response);
            assertThat("Response content",content,is("error message"));
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
        tester.setUserAgent("foo");

        // Configure Gzip Handler
        tester.getGzipHandler().addIncludedMimeTypes("text/plain");
        tester.getGzipHandler().setExcludedAgentPatterns("bar","foo");
        
        // Prepare server file
        int filesize = tester.getOutputBufferSize() * 4;
        tester.prepareServerFile("file.txt",filesize);

        // Add content servlet
        tester.setContentServlet(DefaultServlet.class);

        try
        {
            tester.start();
            assertIsResponseNotGzipCompressed(tester,"GET","file.txt",filesize,HttpStatus.OK_200);
        }
        finally
        {
            tester.stop();
        }
    }
    
    @Test
    public void testUserAgentExclusionDefault() throws Exception
    {
        GzipTester tester = new GzipTester(testingdir,compressionType);
        tester.setContentServlet(DefaultServlet.class);
        tester.setUserAgent("Some MSIE 6.0 user-agent");

        int filesize = tester.getOutputBufferSize() * 4;
        tester.prepareServerFile("file.txt",filesize);

        try
        {
            tester.start();
            HttpTester.Response http = assertIsResponseNotGzipCompressed(tester,"GET","file.txt",filesize,HttpStatus.OK_200);
            Assert.assertEquals("Accept-Encoding, User-Agent",http.get("Vary"));
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
        tester.setUserAgent("foo");

        // Configure Gzip Handler
        tester.getGzipHandler().setExcludedAgentPatterns("bar","fo.*");

        // Prepare server file
        int filesize = tester.getOutputBufferSize() * 4;
        tester.prepareServerFile("file.txt",filesize);

        // Set content servlet
        tester.setContentServlet(DefaultServlet.class);

        try
        {
            tester.start();
            assertIsResponseNotGzipCompressed(tester,"GET","file.txt",filesize,HttpStatus.OK_200);
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

        // Configure Gzip Handler
        tester.getGzipHandler().setExcludedPaths("*.txt");

        // Prepare server file
        int filesize = tester.getOutputBufferSize() * 4;
        tester.prepareServerFile("file.txt",filesize);

        // Set content servlet
        tester.setContentServlet(DefaultServlet.class);

        try
        {
            tester.start();
            assertIsResponseNotGzipCompressed(tester,"GET","file.txt",filesize,HttpStatus.OK_200);
        }
        finally
        {
            tester.stop();
        }
    }

    @Test
    public void testIncludedPaths() throws Exception
    {
        GzipTester tester = new GzipTester(testingdir,compressionType);

        // Configure Gzip Handler
        tester.getGzipHandler().setExcludedPaths("/bad.txt");
        tester.getGzipHandler().setIncludedPaths("*.txt");

        // Prepare server file
        int filesize = tester.getOutputBufferSize() * 4;
        tester.prepareServerFile("file.txt",filesize);
        tester.prepareServerFile("bad.txt",filesize);

        // Set content servlet
        tester.setContentServlet(DefaultServlet.class);

        try
        {
            tester.start();
            tester.assertIsResponseGzipCompressed("GET","file.txt");
        }
        finally
        {
            tester.stop();
        }
        
        try
        {
            tester.start();
            assertIsResponseNotGzipCompressed(tester,"GET","bad.txt",filesize,HttpStatus.OK_200);
        }
        finally
        {
            tester.stop();
        }
    }

    public HttpTester.Response assertIsResponseNotGzipCompressed(GzipTester tester, String method, String filename, int expectedFilesize, int status)
            throws Exception
    {
        HttpTester.Response response = tester.executeRequest(method,"/context/" + filename,5,TimeUnit.SECONDS);

        assertThat("Response status",response.getStatus(),is(status));
        assertThat("Content-Encoding",response.get("Content-Encoding"),not(containsString(compressionType)));

        assertResponseContent(tester,response,status,filename,expectedFilesize);

        return response;
    }

    private void assertResponseContent(GzipTester tester, HttpTester.Response response, int status, String filename, int expectedFilesize) throws IOException,
            UnsupportedEncodingException
    {
        if (expectedFilesize >= 0)
        {
            assertThat("filename",filename,notNullValue());
            assertThat("Response contentBytes.length",response.getContentBytes().length,is(expectedFilesize));
            String contentLength = response.get("Content-Length");
            if (StringUtil.isNotBlank(contentLength))
            {
                assertThat("Content-Length",response.get("Content-Length"),is(Integer.toString(expectedFilesize)));
            }

            if (status >= 200 && status < 300)
            {
                assertThat("ETag",response.get("ETAG"),startsWith("W/"));
            }

            File serverFile = testingdir.getFile(filename);
            String expectedResponse = IO.readToString(serverFile);

            String actual = tester.readResponse(response);
            Assert.assertEquals("Expected response equals actual response",expectedResponse,actual);
        }
    }

    
    @Test
    public void testIsNotGzipCompressedSVGZ() throws Exception
    {
        GzipTester tester = new GzipTester(testingdir,compressionType);

        tester.setContentServlet(DefaultServlet.class);
        tester.copyTestServerFile("test.svgz");
        
        try
        {
            tester.start();
            tester.assertIsResponseNotGzipFiltered("test.svgz","test.svgz.sha1","image/svg+xml","gzip");
        }
        finally
        {
            tester.stop();
        }
    }
}
