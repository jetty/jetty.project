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
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.isEmptyOrNullString;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.Assert.assertThat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.util.EnumSet;
import java.util.Enumeration;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;

import javax.servlet.DispatcherType;
import javax.servlet.Servlet;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.http.DateGenerator;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.http.HttpTester.Response;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.servlet.FilterHolder;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.servlet.ServletTester;
import org.eclipse.jetty.toolchain.test.IO;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.toolchain.test.TestingDir;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.hamcrest.Matchers;
import org.junit.Assert;

public class GzipTester
{
    private static final Logger LOG = Log.getLogger(GzipTester.class);

    public static class ContentMetadata
    {
        public final long size;
        public final String sha1;

        public ContentMetadata(long size, String sha1checksum)
        {
            this.size = size;
            this.sha1 = sha1checksum;
        }
    }

    private String encoding = "ISO8859_1";
    private String userAgent = null;
    private final ServletTester tester = new ServletTester("/context",ServletContextHandler.GZIP);
    private TestingDir testdir;
    private String accept;
    private String compressionType;
    

    public GzipTester(TestingDir testingdir, String compressionType, String accept)
    {
        this.testdir = testingdir;
        this.compressionType = compressionType;
        this.accept = accept;
        
    }

    public GzipTester(TestingDir testingdir, String compressionType)
    {
        this.testdir = testingdir;
        this.compressionType = compressionType;
        this.accept = compressionType;
    }
    
    public GzipHandler getGzipHandler()
    {
        return tester.getContext().getGzipHandler();
    }

    public int getOutputBufferSize()
    {
        return tester.getConnector().getConnectionFactory(HttpConnectionFactory.class).getHttpConfiguration().getOutputBufferSize();
    }
    
    public ContentMetadata getResponseMetadata(Response response) throws Exception
    {
        long size = response.getContentBytes().length;

        String contentEncoding = response.get("Content-Encoding");

        ByteArrayInputStream bais = null;
        InputStream in = null;
        DigestOutputStream digester = null;
        ByteArrayOutputStream uncompressedStream = null;
        try
        {
            MessageDigest digest = MessageDigest.getInstance("SHA1");
            bais = new ByteArrayInputStream(response.getContentBytes());

            if (contentEncoding == null)
            {
                LOG.debug("No response content-encoding");
                in = new PassThruInputStream(bais);
            }
            else if (contentEncoding.contains(GzipHandler.GZIP))
            {
                in = new GZIPInputStream(bais);
            }
            else if (contentEncoding.contains(GzipHandler.DEFLATE))
            {
                in = new InflaterInputStream(bais,new Inflater(true));
            }
            else
            {
                assertThat("Unexpected response content-encoding", contentEncoding, isEmptyOrNullString());
            }
            
            uncompressedStream = new ByteArrayOutputStream((int)size); 

            digester = new DigestOutputStream(uncompressedStream,digest);
            IO.copy(in,digester);
            
            byte output[] = uncompressedStream.toByteArray();
            String actualSha1Sum = Hex.asHex(digest.digest());
            return new ContentMetadata(output.length,actualSha1Sum);
        }
        finally
        {
            IO.close(digester);
            IO.close(in);
            IO.close(bais);
            IO.close(uncompressedStream);
        }
    }

    public HttpTester.Response assertIsResponseGzipCompressed(String method, String filename) throws Exception
    {
        return assertIsResponseGzipCompressed(method,filename,filename,-1);
    }

    public HttpTester.Response assertIsResponseGzipCompressed(String method, String filename, long ifmodifiedsince) throws Exception
    {
        return assertIsResponseGzipCompressed(method,filename,filename,ifmodifiedsince);
    }

    public HttpTester.Response assertIsResponseGzipCompressed(String method, String requestedFilename, String serverFilename) throws Exception
    {
        return assertIsResponseGzipCompressed(method,requestedFilename,serverFilename,-1);
    }

    public HttpTester.Response assertNonStaticContentIsResponseGzipCompressed(String method, String path, String expected) throws Exception
    {
        HttpTester.Request request = HttpTester.newRequest();
        HttpTester.Response response;

        request.setMethod(method);
        request.setVersion("HTTP/1.0");
        request.setHeader("Host","tester");
        request.setHeader("Accept-Encoding",accept);

        if (this.userAgent != null)
            request.setHeader("User-Agent",this.userAgent);
        request.setURI("/context/" + path);

        // Issue the request
        response = HttpTester.parseResponse(tester.getResponses(request.generate()));

        int qindex = compressionType.indexOf(";");
        if (qindex < 0)
            Assert.assertThat("Response.header[Content-Encoding]",response.get("Content-Encoding"),containsString(compressionType));
        else
            Assert.assertThat("Response.header[Content-Encoding]",response.get("Content-Encoding"),containsString(compressionType.substring(0,qindex)));

        ByteArrayInputStream bais = null;
        InputStream in = null;
        ByteArrayOutputStream out = null;
        String actual = null;

        try
        {
            bais = new ByteArrayInputStream(response.getContentBytes());
            if (compressionType.startsWith(GzipHandler.GZIP))
            {
                in = new GZIPInputStream(bais);
            }
            else if (compressionType.startsWith(GzipHandler.DEFLATE))
            {
                in = new InflaterInputStream(bais,new Inflater(true));
            }
            out = new ByteArrayOutputStream();
            IO.copy(in,out);

            actual = out.toString(encoding);
            assertThat("Uncompressed contents",actual,equalTo(expected));
        }
        finally
        {
            IO.close(out);
            IO.close(in);
            IO.close(bais);
        }

        return response;
    }

    public HttpTester.Response assertIsResponseGzipCompressed(String method, String requestedFilename, String serverFilename, long ifmodifiedsince)
            throws Exception
    {
        HttpTester.Request request = HttpTester.newRequest();
        HttpTester.Response response;

        request.setMethod(method);
        request.setVersion("HTTP/1.0");
        request.setHeader("Host","tester");
        request.setHeader("Accept-Encoding",compressionType);
        if (ifmodifiedsince > 0)
            request.setHeader(HttpHeader.IF_MODIFIED_SINCE.asString(),DateGenerator.formatDate(ifmodifiedsince));
        if (this.userAgent != null)
            request.setHeader("User-Agent",this.userAgent);
        request.setURI("/context/" + requestedFilename);

        // Issue the request
        response = HttpTester.parseResponse(tester.getResponses(request.generate()));

        // Assert the response headers
        // Assert.assertThat("Response.status",response.getStatus(),is(HttpServletResponse.SC_OK));

        // Response headers should have either a Transfer-Encoding indicating chunked OR a Content-Length
        /*
         * TODO need to check for the 3rd option of EOF content. To do this properly you might need to look at both HTTP/1.1 and HTTP/1.0 requests String
         * contentLength = response.get("Content-Length"); String transferEncoding = response.get("Transfer-Encoding");
         * 
         * boolean chunked = (transferEncoding != null) && (transferEncoding.indexOf("chunk") >= 0); if(!chunked) {
         * Assert.assertThat("Response.header[Content-Length]",contentLength,notNullValue()); } else {
         * Assert.assertThat("Response.header[Transfer-Encoding]",transferEncoding,notNullValue()); }
         */

        int qindex = compressionType.indexOf(";");
        if (qindex < 0)
            Assert.assertThat("Response.header[Content-Encoding]",response.get("Content-Encoding"),containsString(compressionType));
        else
            Assert.assertThat("Response.header[Content-Encoding]",response.get("Content-Encoding"),containsString(compressionType.substring(0,qindex)));

        Assert.assertThat(response.get("ETag"),Matchers.startsWith("W/"));

        // Assert that the decompressed contents are what we expect.
        File serverFile = testdir.getFile(serverFilename);
        String expected = IO.readToString(serverFile);
        String actual = null;

        ByteArrayInputStream bais = null;
        InputStream in = null;
        ByteArrayOutputStream out = null;
        try
        {
            bais = new ByteArrayInputStream(response.getContentBytes());
            if (compressionType.startsWith(GzipHandler.GZIP))
            {
                in = new GZIPInputStream(bais);
            }
            else if (compressionType.startsWith(GzipHandler.DEFLATE))
            {
                in = new InflaterInputStream(bais,new Inflater(true));
            }
            out = new ByteArrayOutputStream();
            IO.copy(in,out);

            actual = out.toString(encoding);
            assertThat("Uncompressed contents",actual,equalTo(expected));
        }
        finally
        {
            IO.close(out);
            IO.close(in);
            IO.close(bais);
        }

        return response;
    }

    public HttpTester.Response assertIsResponseNotModified(String method, String requestedFilename, long ifmodifiedsince) throws Exception
    {
        HttpTester.Request request = HttpTester.newRequest();
        HttpTester.Response response;

        request.setMethod(method);
        request.setVersion("HTTP/1.0");
        request.setHeader("Host","tester");
        request.setHeader("Accept-Encoding",compressionType);
        if (ifmodifiedsince > 0)
            request.setHeader(HttpHeader.IF_MODIFIED_SINCE.asString(),DateGenerator.formatDate(ifmodifiedsince));
        if (this.userAgent != null)
            request.setHeader("User-Agent",this.userAgent);
        request.setURI("/context/" + requestedFilename);

        // Issue the request
        response = HttpTester.parseResponse(tester.getResponses(request.generate()));

        Assert.assertThat(response.getStatus(),Matchers.equalTo(304));
        Assert.assertThat(response.get("ETag"),Matchers.startsWith("W/"));

        return response;
    }

    /**
     * Makes sure that the response contains an unfiltered file contents.
     * <p>
     * This is used to test exclusions and passthroughs in the GzipHandler.
     * <p>
     * An example is to test that it is possible to configure GzipFilter to not recompress content that shouldn't be compressed by the GzipFilter.
     *
     * @param requestedFilename
     *            the filename used to on the GET request,.
     * @param testResourceSha1Sum
     *            the sha1sum file that contains the SHA1SUM checksum that will be used to verify that the response contents are what is intended.
     * @param expectedContentType the expected content type
     * @throws Exception on test failure
     */
    public void assertIsResponseNotGziped(String requestedFilename, String testResourceSha1Sum, String expectedContentType) throws Exception
    {
        assertIsResponseNotGzipFiltered(requestedFilename,testResourceSha1Sum,expectedContentType,null);
    }

    /**
     * Makes sure that the response contains an unfiltered file contents.
     * <p>
     * This is used to test exclusions and passthroughs in the GzipHandler.
     * <p>
     * An example is to test that it is possible to configure GzipFilter to not recompress content that shouldn't be compressed by the GzipFilter.
     *
     * @param requestedFilename
     *            the filename used to on the GET request,.
     * @param testResourceSha1Sum
     *            the sha1sum file that contains the SHA1SUM checksum that will be used to verify that the response contents are what is intended.
     * @param expectedContentType the expected content type
     * @param expectedContentEncoding
     *            can be non-null in some circumstances, eg when dealing with pre-gzipped .svgz files
     * @throws Exception on test failure
     */
    public void assertIsResponseNotGzipFiltered(String requestedFilename, String testResourceSha1Sum, String expectedContentType, String expectedContentEncoding)
            throws Exception
    {
        HttpTester.Request request = HttpTester.newRequest();
        HttpTester.Response response;

        request.setMethod("GET");
        request.setVersion("HTTP/1.0");
        request.setHeader("Host","tester");
        request.setHeader("Accept-Encoding",compressionType);
        if (this.userAgent != null)
            request.setHeader("User-Agent",this.userAgent);
        request.setURI("/context/" + requestedFilename);

        // Issue the request
        response = HttpTester.parseResponse(tester.getResponses(request.generate()));

        dumpHeaders(requestedFilename + " / Response Headers",response);

        // Assert the response headers
        String prefix = requestedFilename + " / Response";
        Assert.assertThat(prefix + ".status",response.getStatus(),is(HttpServletResponse.SC_OK));
        Assert.assertThat(prefix + ".header[Content-Length]",response.get("Content-Length"),notNullValue());
        Assert.assertThat(prefix + ".header[Content-Encoding] (should not be recompressed by GzipHandler)",response.get("Content-Encoding"),
                expectedContentEncoding == null?nullValue():notNullValue());
        if (expectedContentEncoding != null)
            Assert.assertThat(prefix + ".header[Content-Encoding]",response.get("Content-Encoding"),is(expectedContentEncoding));
        Assert.assertThat(prefix + ".header[Content-Type] (should have a Content-Type associated with it)",response.get("Content-Type"),notNullValue());
        Assert.assertThat(prefix + ".header[Content-Type]",response.get("Content-Type"),is(expectedContentType));

        Assert.assertThat(response.get("ETAG"),Matchers.startsWith("W/"));

        ByteArrayInputStream bais = null;
        DigestOutputStream digester = null;
        try
        {
            MessageDigest digest = MessageDigest.getInstance("SHA1");
            bais = new ByteArrayInputStream(response.getContentBytes());
            digester = new DigestOutputStream(new NoOpOutputStream(),digest);
            IO.copy(bais,digester);

            String actualSha1Sum = Hex.asHex(digest.digest());
            String expectedSha1Sum = loadExpectedSha1Sum(testResourceSha1Sum);
            Assert.assertEquals(requestedFilename + " / SHA1Sum of content",expectedSha1Sum,actualSha1Sum);
        }
        finally
        {
            IO.close(digester);
            IO.close(bais);
        }
    }

    private void dumpHeaders(String prefix, HttpTester.Message message)
    {
        LOG.debug("dumpHeaders: {}",prefix);
        Enumeration<String> names = message.getFieldNames();
        while (names.hasMoreElements())
        {
            String name = names.nextElement();
            String value = message.get(name);
            LOG.debug("dumpHeaders:   {} = {}",name,value);
        }
    }

    private String loadExpectedSha1Sum(String testResourceSha1Sum) throws IOException
    {
        File sha1File = MavenTestingUtils.getTestResourceFile(testResourceSha1Sum);
        String contents = IO.readToString(sha1File);
        Pattern pat = Pattern.compile("^[0-9A-Fa-f]*");
        Matcher mat = pat.matcher(contents);
        Assert.assertTrue("Should have found HEX code in SHA1 file: " + sha1File,mat.find());
        return mat.group();
    }

    public HttpTester.Response executeRequest(String method, String path, int idleFor, TimeUnit idleUnit) throws Exception
    {
        HttpTester.Request request = HttpTester.newRequest();

        request.setMethod(method);
        request.setVersion("HTTP/1.1");
        request.setHeader("Host","tester");
        request.setHeader("Accept-Encoding",accept);
        request.setHeader("Connection","close");

        if (this.userAgent != null)
        {
            request.setHeader("User-Agent",this.userAgent);
        }
        
        request.setURI(path);

        // Issue the request
        return HttpTester.parseResponse(tester.getResponses(request.generate(),idleFor,idleUnit));
    }

    public String readResponse(HttpTester.Response response) throws IOException, UnsupportedEncodingException
    {
        String actual = null;
        InputStream in = null;
        ByteArrayOutputStream out = null;
        try
        {
            byte[] content = response.getContentBytes();
            if (content != null)
                actual = new String(response.getContentBytes(),encoding);
            else
                actual = "";
        }
        finally
        {
            IO.close(out);
            IO.close(in);
        }
        return actual;
    }

    /**
     * Generate string content of arbitrary length.
     *
     * @param length
     *            the length of the string to generate.
     * @return the string content.
     */
    public String generateContent(int length)
    {
        StringBuilder builder = new StringBuilder();
        do
        {
            builder.append("Lorem ipsum dolor sit amet, consectetur adipiscing elit. In quis felis nunc.\n");
            builder.append("Quisque suscipit mauris et ante auctor ornare rhoncus lacus aliquet. Pellentesque\n");
            builder.append("habitant morbi tristique senectus et netus et malesuada fames ac turpis egestas.\n");
            builder.append("Vestibulum sit amet felis augue, vel convallis dolor. Cras accumsan vehicula diam\n");
            builder.append("at faucibus. Etiam in urna turpis, sed congue mi. Morbi et lorem eros. Donec vulputate\n");
            builder.append("velit in risus suscipit lobortis. Aliquam id urna orci, nec sollicitudin ipsum.\n");
            builder.append("Cras a orci turpis. Donec suscipit vulputate cursus. Mauris nunc tellus, fermentum\n");
            builder.append("eu auctor ut, mollis at diam. Quisque porttitor ultrices metus, vitae tincidunt massa\n");
            builder.append("sollicitudin a. Vivamus porttitor libero eget purus hendrerit cursus. Integer aliquam\n");
            builder.append("consequat mauris quis luctus. Cras enim nibh, dignissim eu faucibus ac, mollis nec neque.\n");
            builder.append("Aliquam purus mauris, consectetur nec convallis lacinia, porta sed ante. Suspendisse\n");
            builder.append("et cursus magna. Donec orci enim, molestie a lobortis eu, imperdiet vitae neque.\n");
        }
        while (builder.length() < length);

        // Make sure we are exactly at requested length. (truncate the extra)
        if (builder.length() > length)
        {
            builder.setLength(length);
        }

        return builder.toString();
    }

    public String getEncoding()
    {
        return encoding;
    }

    /**
     * Create a file on the server resource path of a specified filename and size.
     *
     * @param filename
     *            the filename to create
     * @param filesize
     *            the file size to create (Note: this isn't suitable for creating large multi-megabyte files)
     * @return the prepared file
     * @throws IOException if unable to create file
     */
    public File prepareServerFile(String filename, int filesize) throws IOException
    {
        File dir = testdir.getDir();
        File testFile = new File(dir,filename);
        // Make sure we have a uniq filename (to work around windows File.delete bug)
        int i = 0;
        while (testFile.exists())
        {
            testFile = new File(dir,(i++) + "-" + filename);
        }

        FileOutputStream fos = null;
        ByteArrayInputStream in = null;
        try
        {
            fos = new FileOutputStream(testFile,false);
            in = new ByteArrayInputStream(generateContent(filesize).getBytes(encoding));
            IO.copy(in,fos);
            return testFile;
        }
        finally
        {
            IO.close(in);
            IO.close(fos);
        }
    }

    /**
     * Copy a src/test/resource file into the server tree for eventual serving.
     *
     * @param filename
     *            the filename to look for in src/test/resources
     * @throws IOException if unable to copy file
     */
    public void copyTestServerFile(String filename) throws IOException
    {
        File srcFile = MavenTestingUtils.getTestResourceFile(filename);
        File testFile = testdir.getFile(filename);

        IO.copy(srcFile,testFile);
    }

    /**
     * Set the servlet that provides content for the GzipHandler in being tested.
     *
     * @param servletClass
     *            the servlet that will provide content.
     * @throws IOException if unable to set content servlet
     */
    public void setContentServlet(Class<? extends Servlet> servletClass) throws IOException
    {
        tester.setContextPath("/context");
        tester.setResourceBase(testdir.getDir().getCanonicalPath());
        ServletHolder servletHolder = tester.addServlet(servletClass,"/");
        servletHolder.setInitParameter("baseDir",testdir.getDir().getAbsolutePath());
        servletHolder.setInitParameter("etags","true");
    }

    public void setEncoding(String encoding)
    {
        this.encoding = encoding;
    }

    public void setUserAgent(String ua)
    {
        this.userAgent = ua;
    }

    public void addMimeType(String extension, String mimetype)
    {
        this.tester.getContext().getMimeTypes().addMimeMapping(extension,mimetype);
    }

    /**
     * Add an arbitrary filter to the test case.
     * 
     * @param holder
     *            the filter to add
     * @param pathSpec
     *            the path spec for this filter
     * @param dispatches
     *            the set of {@link DispatcherType} to associate with this filter
     * @throws IOException if unable to add filter
     */
    public void addFilter(FilterHolder holder, String pathSpec, EnumSet<DispatcherType> dispatches) throws IOException
    {
        tester.addFilter(holder,pathSpec,dispatches);
    }

    public void start() throws Exception
    {
        Assert.assertThat("No servlet defined yet.  Did you use #setContentServlet()?",tester,notNullValue());
        
        if (LOG.isDebugEnabled())
        {
            tester.dumpStdErr();
        }
        tester.start();
    }

    public void stop()
    {
        // NOTE: Do not cleanup the testdir. Failures can't be diagnosed if you do that.
        // IO.delete(testdir.getDir()):
        try
        {
            tester.stop();
        }
        catch (Exception e)
        {
            // Don't toss this out into Junit as this would be the last exception
            // that junit will report as being the cause of the test failure.
            // when in reality, the earlier setup issue is the real cause.
            e.printStackTrace(System.err);
        }
    }

}
