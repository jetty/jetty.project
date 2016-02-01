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

package org.eclipse.jetty.servlets;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertThat;

import java.io.File;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.TimeUnit;

import javax.servlet.DispatcherType;

import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.handler.gzip.AsyncManipFilter;
import org.eclipse.jetty.server.handler.gzip.AsyncScheduledDispatchWrite;
import org.eclipse.jetty.server.handler.gzip.AsyncTimeoutCompleteWrite;
import org.eclipse.jetty.server.handler.gzip.AsyncTimeoutDispatchWrite;
import org.eclipse.jetty.server.handler.gzip.GzipHandler;
import org.eclipse.jetty.server.handler.gzip.GzipTester;
import org.eclipse.jetty.server.handler.gzip.GzipTester.ContentMetadata;
import org.eclipse.jetty.server.handler.gzip.TestDirContentServlet;
import org.eclipse.jetty.server.handler.gzip.TestServletLengthStreamTypeWrite;
import org.eclipse.jetty.servlet.FilterHolder;
import org.eclipse.jetty.toolchain.test.TestTracker;
import org.eclipse.jetty.toolchain.test.TestingDir;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameter;
import org.junit.runners.Parameterized.Parameters;

/**
 * Test the GzipFilter support when under several layers of Filters.
 */
@RunWith(Parameterized.class)
@Ignore
public class GzipFilterLayeredTest
{
    @Rule
    public final TestTracker tracker = new TestTracker();
    
    private static final HttpConfiguration defaultHttp = new HttpConfiguration();
    private static final int LARGE = defaultHttp.getOutputBufferSize() * 8;
    private static final int SMALL = defaultHttp.getOutputBufferSize() / 4;
    private static final int TINY = GzipHandler.DEFAULT_MIN_GZIP_SIZE / 2;
    private static final boolean EXPECT_COMPRESSED = true;

    @Parameters(name = "{0} bytes - {1} - compressed({2}) - filter({3}) - servlet({4}")
    public static List<Object[]> data()
    {
        List<Object[]> ret = new ArrayList<Object[]>();
        
        Class<?> gzipFilters[] = new Class<?>[] { GzipFilter.class, AsyncGzipFilter.class };
        Class<?> contentServlets[] = new Class<?>[] { 
                TestServletLengthStreamTypeWrite.class, 
                AsyncTimeoutDispatchWrite.Default.class,
                AsyncTimeoutDispatchWrite.Passed.class,
                AsyncTimeoutCompleteWrite.Default.class,
                AsyncTimeoutCompleteWrite.Passed.class,
                AsyncScheduledDispatchWrite.Default.class,
                AsyncScheduledDispatchWrite.Passed.class,
                };

        for (Class<?> contentServlet: contentServlets)
        {
            for (Class<?> gzipFilter : gzipFilters)
            {
                ret.add(new Object[] { 0, "empty.txt", !EXPECT_COMPRESSED, gzipFilter, contentServlet });
                ret.add(new Object[] { TINY, "file-tiny.txt", !EXPECT_COMPRESSED, gzipFilter, contentServlet });
                ret.add(new Object[] { SMALL, "file-small.txt", EXPECT_COMPRESSED, gzipFilter, contentServlet });
                ret.add(new Object[] { LARGE, "file-large.txt", EXPECT_COMPRESSED, gzipFilter, contentServlet });
                ret.add(new Object[] { LARGE, "file-large.mp3", !EXPECT_COMPRESSED, gzipFilter, contentServlet });
            }
        }

        return ret;
    }

    @Parameter(0)
    public int fileSize;
    @Parameter(1)
    public String fileName;
    @Parameter(2)
    public boolean expectCompressed;
    @Parameter(3)
    public Class<? extends GzipFilter> gzipFilterClass;
    @Parameter(4)
    public Class<? extends TestDirContentServlet> contentServletClass;

    @Rule
    public TestingDir testingdir = new TestingDir();
    
    @Test
    public void testGzipDos() throws Exception
    {
        GzipTester tester = new GzipTester(testingdir, GzipHandler.GZIP);
        
        // Add Gzip Filter first
        FilterHolder gzipHolder = new FilterHolder(gzipFilterClass);
        gzipHolder.setAsyncSupported(true);
        tester.addFilter(gzipHolder,"*.txt",EnumSet.of(DispatcherType.REQUEST,DispatcherType.ASYNC));
        tester.addFilter(gzipHolder,"*.mp3",EnumSet.of(DispatcherType.REQUEST,DispatcherType.ASYNC));
        gzipHolder.setInitParameter("mimeTypes","text/plain");

        // Add (DoSFilter-like) manip filter (in chain of Gzip)
        FilterHolder manipHolder = new FilterHolder(AsyncManipFilter.class);
        manipHolder.setAsyncSupported(true);
        tester.addFilter(manipHolder,"/*",EnumSet.of(DispatcherType.REQUEST,DispatcherType.ASYNC));
        
        // Add content servlet
        tester.setContentServlet(contentServletClass);
        
        try
        {
            String testFilename = String.format("GzipDos-%s-%s",contentServletClass.getSimpleName(),fileName);
            File testFile = tester.prepareServerFile(testFilename,fileSize);
            
            tester.start();
            
            HttpTester.Response response = tester.executeRequest("GET","/context/" + testFile.getName(),5,TimeUnit.SECONDS);
            
            assertThat("Response status", response.getStatus(), is(HttpStatus.OK_200));
            
            if (expectCompressed)
            {
                // Must be gzip compressed
                assertThat("Content-Encoding",response.get("Content-Encoding"),containsString(GzipHandler.GZIP));
            }
            
            // Uncompressed content Size
            ContentMetadata content = tester.getResponseMetadata(response);
            assertThat("(Uncompressed) Content Length", content.size, is((long)fileSize));
        }
        finally
        {
            tester.stop();
        }
    }
    
    @Test
    public void testDosGzip() throws Exception
    {
        GzipTester tester = new GzipTester(testingdir, GzipHandler.GZIP);
        
        // Add (DoSFilter-like) manip filter
        FilterHolder manipHolder = new FilterHolder(AsyncManipFilter.class);
        manipHolder.setAsyncSupported(true);
        tester.addFilter(manipHolder,"/*",EnumSet.of(DispatcherType.REQUEST,DispatcherType.ASYNC));
        
        // Add Gzip Filter first (in chain of DosFilter)
        FilterHolder gzipHolder = new FilterHolder(gzipFilterClass);
        gzipHolder.setAsyncSupported(true);
        tester.addFilter(gzipHolder,"*.txt",EnumSet.of(DispatcherType.REQUEST,DispatcherType.ASYNC));
        tester.addFilter(gzipHolder,"*.mp3",EnumSet.of(DispatcherType.REQUEST,DispatcherType.ASYNC));
        gzipHolder.setInitParameter("mimeTypes","text/plain");

        // Add content servlet
        tester.setContentServlet(contentServletClass);
        
        try
        {
            String testFilename = String.format("DosGzip-%s-%s",contentServletClass.getSimpleName(),fileName);
            File testFile = tester.prepareServerFile(testFilename,fileSize);
            
            tester.start();
            
            HttpTester.Response response = tester.executeRequest("GET","/context/" + testFile.getName(),5,TimeUnit.SECONDS);
            
            assertThat("Response status", response.getStatus(), is(HttpStatus.OK_200));
            
            if (expectCompressed)
            {
                // Must be gzip compressed
                assertThat("Content-Encoding",response.get("Content-Encoding"),containsString(GzipHandler.GZIP));
            } else
            {
                assertThat("Content-Encoding",response.get("Content-Encoding"),not(containsString(GzipHandler.GZIP)));
            }
            
            // Uncompressed content Size
            ContentMetadata content = tester.getResponseMetadata(response);
            assertThat("(Uncompressed) Content Length", content.size, is((long)fileSize));
        }
        finally
        {
            tester.stop();
        }
    }
}    
