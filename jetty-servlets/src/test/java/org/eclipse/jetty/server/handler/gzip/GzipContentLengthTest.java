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

package org.eclipse.jetty.server.handler.gzip;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.handler.gzip.GzipTester.ContentMetadata;
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
 * Test the GzipHandler support for Content-Length setting variations.
 *
 * @see <a href="Eclipse Bug 354014">https://bugs.eclipse.org/354014</a>
 */
@ExtendWith(WorkDirExtension.class)
public class GzipContentLengthTest
{
    public WorkDir workDir;

    private static final HttpConfiguration defaultHttp = new HttpConfiguration();
    private static final int LARGE = defaultHttp.getOutputBufferSize() * 8;
    private static final int MEDIUM = defaultHttp.getOutputBufferSize();
    private static final int SMALL = defaultHttp.getOutputBufferSize() / 4;
    private static final int TINY = GzipHandler.DEFAULT_MIN_GZIP_SIZE / 2;
    private static final boolean EXPECT_COMPRESSED = true;

    public static Stream<Arguments> scenarios()
    {
        List<Scenario> ret = new ArrayList<>();

        ret.add(new Scenario(0, "empty.txt", !EXPECT_COMPRESSED));
        ret.add(new Scenario(TINY, "file-tiny.txt", !EXPECT_COMPRESSED));
        ret.add(new Scenario(SMALL, "file-small.txt", EXPECT_COMPRESSED));
        ret.add(new Scenario(SMALL, "file-small.mp3", !EXPECT_COMPRESSED));
        ret.add(new Scenario(MEDIUM, "file-med.txt", EXPECT_COMPRESSED));
        ret.add(new Scenario(MEDIUM, "file-medium.mp3", !EXPECT_COMPRESSED));
        ret.add(new Scenario(LARGE, "file-large.txt", EXPECT_COMPRESSED));
        ret.add(new Scenario(LARGE, "file-large.mp3", !EXPECT_COMPRESSED));

        return ret.stream().map(Arguments::of);
    }

    private void testWithGzip(Scenario scenario, Class<? extends TestDirContentServlet> contentServlet) throws Exception
    {
        GzipTester tester = new GzipTester(workDir.getPath(), GzipHandler.GZIP);

        // Add AsyncGzip Configuration
        tester.getGzipHandler().setIncludedMimeTypes("text/plain");
        tester.getGzipHandler().setIncludedPaths("*.txt", "*.mp3");

        // Add content servlet
        tester.setContentServlet(contentServlet);

        try
        {
            String testFilename = String.format("%s-%s", contentServlet.getSimpleName(), scenario.fileName);
            File testFile = tester.prepareServerFile(testFilename, scenario.fileSize);

            tester.start();

            HttpTester.Response response = tester.executeRequest("GET", "/context/" + testFile.getName(), 5, TimeUnit.SECONDS);

            if (response.getStatus() != 200)
                System.err.println("DANG!!!! " + response);

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

    /**
     * Test with content servlet that does:
     * AsyncContext create -> timeout -> onTimeout -> write-response -> complete
     *
     * @throws Exception on test failure
     */
    @ParameterizedTest
    @MethodSource("scenarios")
    public void testAsyncTimeoutCompleteWriteDefault(Scenario scenario) throws Exception
    {
        testWithGzip(scenario, AsyncTimeoutCompleteWrite.Default.class);
    }

    /**
     * Test with content servlet that does:
     * AsyncContext create -> timeout -> onTimeout -> write-response -> complete
     *
     * @throws Exception on test failure
     */
    @ParameterizedTest
    @MethodSource("scenarios")
    public void testAsyncTimeoutCompleteWritePassed(Scenario scenario) throws Exception
    {
        testWithGzip(scenario, AsyncTimeoutCompleteWrite.Passed.class);
    }

    /**
     * Test with content servlet that does:
     * AsyncContext create -> timeout -> onTimeout -> dispatch -> write-response
     *
     * @throws Exception on test failure
     */
    @ParameterizedTest
    @MethodSource("scenarios")
    public void testAsyncTimeoutDispatchWriteDefault(Scenario scenario) throws Exception
    {
        testWithGzip(scenario, AsyncTimeoutDispatchWrite.Default.class);
    }

    /**
     * Test with content servlet that does:
     * AsyncContext create -> timeout -> onTimeout -> dispatch -> write-response
     *
     * @throws Exception on test failure
     */
    @ParameterizedTest
    @MethodSource("scenarios")
    public void testAsyncTimeoutDispatchWritePassed(Scenario scenario) throws Exception
    {
        testWithGzip(scenario, AsyncTimeoutDispatchWrite.Passed.class);
    }

    /**
     * Test with content servlet that does:
     * AsyncContext create -> no-timeout -> scheduler.schedule -> dispatch -> write-response
     *
     * @throws Exception on test failure
     */
    @ParameterizedTest
    @MethodSource("scenarios")
    public void testAsyncScheduledDispatchWriteDefault(Scenario scenario) throws Exception
    {
        testWithGzip(scenario, AsyncScheduledDispatchWrite.Default.class);
    }

    /**
     * Test with content servlet that does:
     * AsyncContext create -> no-timeout -> scheduler.schedule -> dispatch -> write-response
     *
     * @throws Exception on test failure
     */
    @ParameterizedTest
    @MethodSource("scenarios")
    public void testAsyncScheduledDispatchWritePassed(Scenario scenario) throws Exception
    {
        testWithGzip(scenario, AsyncScheduledDispatchWrite.Passed.class);
    }

    /**
     * Test with content servlet that does:
     * 1) setHeader(content-length)
     * 2) getOutputStream()
     * 3) setHeader(content-type)
     * 4) outputStream.write()
     *
     * @throws Exception on test failure
     * @see <a href="https://bugs.eclipse.org/354014">Eclipse Bug 354014</a>
     */
    @ParameterizedTest
    @MethodSource("scenarios")
    public void testServletLengthStreamTypeWrite(Scenario scenario) throws Exception
    {
        testWithGzip(scenario, TestServletLengthStreamTypeWrite.class);
    }

    /**
     * Test with content servlet that does:
     * 1) setHeader(content-length)
     * 2) setHeader(content-type)
     * 3) getOutputStream()
     * 4) outputStream.write()
     *
     * @throws Exception on test failure
     * @see <a href="https://bugs.eclipse.org/354014">Eclipse Bug 354014</a>
     */
    @ParameterizedTest
    @MethodSource("scenarios")
    public void testServletLengthTypeStreamWrite(Scenario scenario) throws Exception
    {
        testWithGzip(scenario, TestServletLengthTypeStreamWrite.class);
    }

    /**
     * Test with content servlet that does:
     * 1) getOutputStream()
     * 2) setHeader(content-length)
     * 3) setHeader(content-type)
     * 4) outputStream.write()
     *
     * @throws Exception on test failure
     * @see <a href="https://bugs.eclipse.org/354014">Eclipse Bug 354014</a>
     */
    @ParameterizedTest
    @MethodSource("scenarios")
    public void testServletStreamLengthTypeWrite(Scenario scenario) throws Exception
    {
        testWithGzip(scenario, TestServletStreamLengthTypeWrite.class);
    }

    /**
     * Test with content servlet that does:
     * 1) getOutputStream()
     * 2) setHeader(content-length)
     * 3) setHeader(content-type)
     * 4) outputStream.write() (with frequent response flush)
     *
     * @throws Exception on test failure
     * @see <a href="https://bugs.eclipse.org/354014">Eclipse Bug 354014</a>
     */
    @ParameterizedTest
    @MethodSource("scenarios")
    public void testServletStreamLengthTypeWriteWithFlush(Scenario scenario) throws Exception
    {
        testWithGzip(scenario, TestServletStreamLengthTypeWriteWithFlush.class);
    }

    /**
     * Test with content servlet that does:
     * 1) getOutputStream()
     * 2) setHeader(content-type)
     * 3) setHeader(content-length)
     * 4) outputStream.write()
     *
     * @throws Exception on test failure
     * @see <a href="https://bugs.eclipse.org/354014">Eclipse Bug 354014</a>
     */
    @ParameterizedTest
    @MethodSource("scenarios")
    public void testServletStreamTypeLengthWrite(Scenario scenario) throws Exception
    {
        testWithGzip(scenario, TestServletStreamTypeLengthWrite.class);
    }

    /**
     * Test with content servlet that does:
     * 1) setHeader(content-type)
     * 2) setHeader(content-length)
     * 3) getOutputStream()
     * 4) outputStream.write()
     *
     * @throws Exception on test failure
     * @see <a href="https://bugs.eclipse.org/354014">Eclipse Bug 354014</a>
     */
    @ParameterizedTest
    @MethodSource("scenarios")
    public void testServletTypeLengthStreamWrite(Scenario scenario) throws Exception
    {
        testWithGzip(scenario, TestServletTypeLengthStreamWrite.class);
    }

    /**
     * Test with content servlet that does:
     * 1) setHeader(content-type)
     * 2) getOutputStream()
     * 3) setHeader(content-length)
     * 4) outputStream.write()
     *
     * @throws Exception on test failure
     * @see <a href="Eclipse Bug 354014">https://bugs.eclipse.org/354014</a>
     */
    @ParameterizedTest
    @MethodSource("scenarios")
    public void testServletTypeStreamLengthWrite(Scenario scenario) throws Exception
    {
        testWithGzip(scenario, TestServletTypeStreamLengthWrite.class);
    }

    /**
     * Test with content servlet that does:
     * 2) getOutputStream()
     * 1) setHeader(content-type)
     * 3) setHeader(content-length)
     * 4) (unwrapped) HttpOutput.write(ByteBuffer)
     *
     * This is done to demonstrate a bug with using HttpOutput.write()
     * while also using GzipFilter
     *
     * @throws Exception on test failure
     * @see <a href="https://bugs.eclipse.org/450873">Eclipse Bug 450873</a>
     */
    @ParameterizedTest
    @MethodSource("scenarios")
    public void testHttpOutputWrite(Scenario scenario) throws Exception
    {
        testWithGzip(scenario, TestServletBufferTypeLengthWrite.class);
    }

    public static class Scenario
    {
        final int fileSize;
        final String fileName;
        final boolean expectCompressed;

        public Scenario(int fileSize, String fileName, boolean expectCompressed)
        {
            this.fileSize = fileSize;
            this.fileName = fileName;
            this.expectCompressed = expectCompressed;
        }

        @Override
        public String toString()
        {
            return String.format("%s [%,d bytes, compressed=%b]", fileName, fileSize, expectCompressed);
        }
    }
}
