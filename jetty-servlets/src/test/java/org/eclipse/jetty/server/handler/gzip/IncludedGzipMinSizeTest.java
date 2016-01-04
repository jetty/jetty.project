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

import javax.servlet.Servlet;

import org.eclipse.jetty.toolchain.test.TestingDir;
import org.junit.Rule;
import org.junit.Test;

/**
 * Perform specific tests on the IncludableGzipHandler's ability to manage
 * minGzipSize initialization parameter.
 *
 * @see <a href="Eclipse Bug 366106">http://bugs.eclipse.org/366106</a>
 */
public class IncludedGzipMinSizeTest
{
    public IncludedGzipMinSizeTest()
    {
        this.compressionType = GzipHandler.GZIP;
    }

    @Rule
    public TestingDir testdir = new TestingDir();

    private String compressionType;
    private Class<? extends Servlet> testServlet = TestMinGzipSizeServlet.class;

    @Test
    public void testUnderMinSize() throws Exception
    {
        GzipTester tester = new GzipTester(testdir, compressionType);

        tester.setContentServlet(testServlet);
        // A valid mime type that we will never use in this test.
        // configured here to prevent mimeType==null logic
        tester.getGzipHandler().addIncludedMimeTypes("application/soap+xml");
        tester.getGzipHandler().setMinGzipSize(2048);

        tester.copyTestServerFile("small_script.js");

        try {
            tester.start();
            tester.assertIsResponseNotGziped("small_script.js",
                    "small_script.js.sha1",
                    "text/javascript; charset=utf-8");
        } finally {
            tester.stop();
        }
    }

    @Test
    public void testOverMinSize() throws Exception
    {
        GzipTester tester = new GzipTester(testdir, compressionType);

        tester.setContentServlet(testServlet);
        tester.getGzipHandler().addIncludedMimeTypes("application/soap+xml","text/javascript","application/javascript");
        tester.getGzipHandler().setMinGzipSize(2048);

        tester.copyTestServerFile("big_script.js");

        try {
            tester.start();
            tester.assertIsResponseGzipCompressed("GET","big_script.js");
        } finally {
            tester.stop();
        }
    }
}
