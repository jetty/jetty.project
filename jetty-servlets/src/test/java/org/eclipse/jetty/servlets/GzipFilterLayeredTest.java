//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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

import java.io.File;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import javax.servlet.DispatcherType;
import javax.servlet.Filter;
import javax.servlet.Servlet;

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
import org.eclipse.jetty.server.handler.gzip.TestServletLengthStreamTypeWrite;
import org.eclipse.jetty.servlet.FilterHolder;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDir;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDirExtension;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

/**
 * Test the GzipFilter support when under several layers of Filters.
 */
@ExtendWith(WorkDirExtension.class)
public class GzipFilterLayeredTest
{
    private static final HttpConfiguration defaultHttp = new HttpConfiguration();
    private static final int LARGE = defaultHttp.getOutputBufferSize() * 8;
    private static final int SMALL = defaultHttp.getOutputBufferSize() / 4;
    private static final int TINY = GzipHandler.DEFAULT_MIN_GZIP_SIZE / 2;
    private static final boolean EXPECT_COMPRESSED = true;

    public static Stream<Arguments> scenarios()
    {
        List<Arguments> ret = new ArrayList<>();

        List<Class<?>> gzipFilters = new ArrayList<>();
        gzipFilters.add(GzipFilter.class);
        gzipFilters.add(AsyncGzipFilter.class);

        List<Class<?>> contentServlets = new ArrayList<>();
        contentServlets.add(TestServletLengthStreamTypeWrite.class);
        contentServlets.add(AsyncTimeoutDispatchWrite.Default.class);
        contentServlets.add(AsyncTimeoutDispatchWrite.Passed.class);
        contentServlets.add(AsyncTimeoutCompleteWrite.Default.class);
        contentServlets.add(AsyncTimeoutCompleteWrite.Passed.class);
        contentServlets.add(AsyncScheduledDispatchWrite.Default.class);
        contentServlets.add(AsyncScheduledDispatchWrite.Passed.class);

        for (Class<?> contentServlet : contentServlets)
        {
            for (Class<?> gzipFilter : gzipFilters)
            {
                ret.add(Arguments.of(new Scenario(0, "empty.txt", !EXPECT_COMPRESSED, gzipFilter, contentServlet)));
                ret.add(Arguments.of(new Scenario(TINY, "file-tiny.txt", !EXPECT_COMPRESSED, gzipFilter, contentServlet)));
                ret.add(Arguments.of(new Scenario(SMALL, "file-small.txt", EXPECT_COMPRESSED, gzipFilter, contentServlet)));
                ret.add(Arguments.of(new Scenario(LARGE, "file-large.txt", EXPECT_COMPRESSED, gzipFilter, contentServlet)));
                ret.add(Arguments.of(new Scenario(LARGE, "file-large.mp3", !EXPECT_COMPRESSED, gzipFilter, contentServlet)));
            }
        }

        return ret.stream();
    }

    public WorkDir testingdir;

    @ParameterizedTest
    @MethodSource("scenarios")
    public void testGzipDos(Scenario scenario) throws Exception
    {
        GzipTester tester = new GzipTester(testingdir.getEmptyPathDir(), GzipHandler.GZIP);

        // Add Gzip Filter first
        FilterHolder gzipHolder = new FilterHolder(scenario.gzipFilterClass);
        gzipHolder.setAsyncSupported(true);
        tester.addFilter(gzipHolder, "*.txt", EnumSet.of(DispatcherType.REQUEST, DispatcherType.ASYNC));
        tester.addFilter(gzipHolder, "*.mp3", EnumSet.of(DispatcherType.REQUEST, DispatcherType.ASYNC));
        gzipHolder.setInitParameter("mimeTypes", "text/plain");

        // Add (DoSFilter-like) manip filter (in chain of Gzip)
        FilterHolder manipHolder = new FilterHolder(AsyncManipFilter.class);
        manipHolder.setAsyncSupported(true);
        tester.addFilter(manipHolder, "/*", EnumSet.of(DispatcherType.REQUEST, DispatcherType.ASYNC));

        // Add content servlet
        tester.setContentServlet(scenario.contentServletClass);

        try
        {
            String testFilename = String.format("GzipDos-%s-%s", scenario.contentServletClass.getSimpleName(), scenario.fileName);
            File testFile = tester.prepareServerFile(testFilename, scenario.fileSize);

            tester.start();

            HttpTester.Response response = tester.executeRequest("GET", "/context/" + testFile.getName(), 5, TimeUnit.SECONDS);

            assertThat("Response status", response.getStatus(), is(HttpStatus.OK_200));

            if (scenario.expectCompressed)
            {
                // Must be gzip compressed
                assertThat("Content-Encoding", response.get("Content-Encoding"), containsString(GzipHandler.GZIP));
            }

            // Uncompressed content Size
            ContentMetadata content = tester.getResponseMetadata(response);
            assertThat("(Uncompressed) Content Length", content.size, is((long)scenario.fileSize));
        }
        finally
        {
            tester.stop();
        }
    }

    @ParameterizedTest
    @MethodSource("scenarios")
    public void testDosGzip(Scenario scenario) throws Exception
    {
        GzipTester tester = new GzipTester(testingdir.getPath(), GzipHandler.GZIP);

        // Add (DoSFilter-like) manip filter
        FilterHolder manipHolder = new FilterHolder(AsyncManipFilter.class);
        manipHolder.setAsyncSupported(true);
        tester.addFilter(manipHolder, "/*", EnumSet.of(DispatcherType.REQUEST, DispatcherType.ASYNC));

        // Add Gzip Filter first (in chain of DosFilter)
        FilterHolder gzipHolder = new FilterHolder(scenario.gzipFilterClass);
        gzipHolder.setAsyncSupported(true);
        tester.addFilter(gzipHolder, "*.txt", EnumSet.of(DispatcherType.REQUEST, DispatcherType.ASYNC));
        tester.addFilter(gzipHolder, "*.mp3", EnumSet.of(DispatcherType.REQUEST, DispatcherType.ASYNC));
        gzipHolder.setInitParameter("mimeTypes", "text/plain");

        // Add content servlet
        tester.setContentServlet(scenario.contentServletClass);

        try
        {
            String testFilename = String.format("DosGzip-%s-%s", scenario.contentServletClass.getSimpleName(), scenario.fileName);
            File testFile = tester.prepareServerFile(testFilename, scenario.fileSize);

            tester.start();

            HttpTester.Response response = tester.executeRequest("GET", "/context/" + testFile.getName(), 5, TimeUnit.SECONDS);

            assertThat("Response status", response.getStatus(), is(HttpStatus.OK_200));

            if (scenario.expectCompressed)
            {
                // Must be gzip compressed
                assertThat("Content-Encoding", response.get("Content-Encoding"), containsString(GzipHandler.GZIP));
            }
            else
            {
                assertThat("Content-Encoding", response.get("Content-Encoding"), not(containsString(GzipHandler.GZIP)));
            }

            // Uncompressed content Size
            ContentMetadata content = tester.getResponseMetadata(response);
            assertThat("(Uncompressed) Content Length", content.size, is((long)scenario.fileSize));
        }
        finally
        {
            tester.stop();
        }
    }

    public static class Scenario
    {
        public int fileSize;
        public String fileName;
        public boolean expectCompressed;
        public Class<? extends Filter> gzipFilterClass;
        public Class<? extends Servlet> contentServletClass;

        public Scenario(int fileSize, String fileName, boolean expectCompressed, Class<?> gzipFilterClass, Class<?> contentServletClass)
        {
            this.fileSize = fileSize;
            this.fileName = fileName;
            this.expectCompressed = expectCompressed;
            this.gzipFilterClass = (Class<? extends Filter>)gzipFilterClass;
            this.contentServletClass = (Class<? extends Servlet>)contentServletClass;
        }

        @Override
        public String toString()
        {
            return String.format("%,d bytes - %s - compressed(%b) - filter(%s) - servlet(%s)",
                fileSize, fileName, expectCompressed, gzipFilterClass, contentServletClass
            );
        }
    }
}    
