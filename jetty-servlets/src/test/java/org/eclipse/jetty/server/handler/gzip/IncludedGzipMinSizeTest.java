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

import javax.servlet.Servlet;

import org.eclipse.jetty.toolchain.test.jupiter.WorkDir;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDirExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Perform specific tests on the IncludableGzipHandler's ability to manage
 * minGzipSize initialization parameter.
 *
 * @see <a href="Eclipse Bug 366106">https://bugs.eclipse.org/366106</a>
 */
@ExtendWith(WorkDirExtension.class)
public class IncludedGzipMinSizeTest
{
    public IncludedGzipMinSizeTest()
    {
        this.compressionType = GzipHandler.GZIP;
    }

    public WorkDir testdir;

    private String compressionType;
    private Class<? extends Servlet> testServlet = TestMinGzipSizeServlet.class;

    @Test
    public void testUnderMinSize() throws Exception
    {
        GzipTester tester = new GzipTester(testdir.getEmptyPathDir(), compressionType);

        tester.setContentServlet(testServlet);
        // A valid mime type that we will never use in this test.
        // configured here to prevent mimeType==null logic
        tester.getGzipHandler().addIncludedMimeTypes("application/soap+xml");
        tester.getGzipHandler().setMinGzipSize(2048);

        tester.copyTestServerFile("small_script.js");

        try
        {
            tester.start();
            tester.assertIsResponseNotGziped("small_script.js",
                "small_script.js.sha1",
                "text/javascript; charset=utf-8");
        }
        finally
        {
            tester.stop();
        }
    }

    @Test
    public void testOverMinSize() throws Exception
    {
        GzipTester tester = new GzipTester(testdir.getEmptyPathDir(), compressionType);

        tester.setContentServlet(testServlet);
        tester.getGzipHandler().addIncludedMimeTypes("application/soap+xml", "text/javascript", "application/javascript");
        tester.getGzipHandler().setMinGzipSize(2048);

        tester.copyTestServerFile("big_script.js");

        try
        {
            tester.start();
            tester.assertIsResponseGzipCompressed("GET", "big_script.js");
        }
        finally
        {
            tester.stop();
        }
    }
}
