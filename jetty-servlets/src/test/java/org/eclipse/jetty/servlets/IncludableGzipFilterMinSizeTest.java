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

import java.util.Arrays;
import java.util.Collection;

import javax.servlet.Servlet;

import org.eclipse.jetty.servlet.FilterHolder;
import org.eclipse.jetty.servlets.gzip.GzipTester;
import org.eclipse.jetty.servlets.gzip.TestMinGzipSizeServlet;
import org.eclipse.jetty.toolchain.test.TestingDir;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

/**
 * Perform specific tests on the IncludableGzipFilter's ability to manage
 * minGzipSize initialization parameter.
 *
 * @see <a href="Eclipse Bug 366106">http://bugs.eclipse.org/366106</a>
 */
@RunWith(Parameterized.class)
public class IncludableGzipFilterMinSizeTest
{
    @Parameters
    public static Collection<String[]> data()
    {
        String[][] data = new String[][]
                {
                { GzipFilter.GZIP },
                { GzipFilter.DEFLATE }
                };

        return Arrays.asList(data);
    }

    public IncludableGzipFilterMinSizeTest(String compressionType)
    {
        this.compressionType = compressionType;
    }

    @Rule
    public TestingDir testdir = new TestingDir();

    private String compressionType;
    private Class<? extends Servlet> testServlet = TestMinGzipSizeServlet.class;

    @Test
    public void testUnderMinSize() throws Exception
    {
        GzipTester tester = new GzipTester(testdir, compressionType);
        // Use IncludableGzipFilter
        tester.setGzipFilterClass(IncludableGzipFilter.class);

        FilterHolder holder = tester.setContentServlet(testServlet);
        // A valid mime type that we will never use in this test.
        // configured here to prevent mimeType==null logic
        holder.setInitParameter("mimeTypes","application/soap+xml");
        holder.setInitParameter("minGzipSize", "2048");
        holder.setInitParameter("uncheckedPrintWriter","true");

        tester.copyTestServerFile("small_script.js");

        try {
            tester.start();
            tester.assertIsResponseNotGzipFiltered("small_script.js",
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
        // Use IncludableGzipFilter
        tester.setGzipFilterClass(IncludableGzipFilter.class);

        FilterHolder holder = tester.setContentServlet(testServlet);
        holder.setInitParameter("mimeTypes","application/soap+xml,text/javascript,application/javascript");
        holder.setInitParameter("minGzipSize", "2048");
        holder.setInitParameter("uncheckedPrintWriter","true");

        tester.copyTestServerFile("big_script.js");

        try {
            tester.start();
            tester.assertIsResponseGzipCompressed("GET","big_script.js");
        } finally {
            tester.stop();
        }
    }
}
