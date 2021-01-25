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

import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.zip.GZIPInputStream;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.servlet.ServletTester;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDir;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDirExtension;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.IO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.junit.jupiter.api.Assertions.assertEquals;

@ExtendWith(WorkDirExtension.class)
public class IncludedGzipTest
{
    public WorkDir testdir;

    private static String __content =
        "Lorem ipsum dolor sit amet, consectetur adipiscing elit. In quis felis nunc. " +
            "Quisque suscipit mauris et ante auctor ornare rhoncus lacus aliquet. Pellentesque " +
            "habitant morbi tristique senectus et netus et malesuada fames ac turpis egestas. " +
            "Vestibulum sit amet felis augue, vel convallis dolor. Cras accumsan vehicula diam " +
            "at faucibus. Etiam in urna turpis, sed congue mi. Morbi et lorem eros. Donec vulputate " +
            "velit in risus suscipit lobortis. Aliquam id urna orci, nec sollicitudin ipsum. " +
            "Cras a orci turpis. Donec suscipit vulputate cursus. Mauris nunc tellus, fermentum " +
            "eu auctor ut, mollis at diam. Quisque porttitor ultrices metus, vitae tincidunt massa " +
            "sollicitudin a. Vivamus porttitor libero eget purus hendrerit cursus. Integer aliquam " +
            "consequat mauris quis luctus. Cras enim nibh, dignissim eu faucibus ac, mollis nec neque. " +
            "Aliquam purus mauris, consectetur nec convallis lacinia, porta sed ante. Suspendisse " +
            "et cursus magna. Donec orci enim, molestie a lobortis eu, imperdiet vitae neque.";

    private ServletTester tester;
    private String compressionType;

    public IncludedGzipTest()
    {
        this.compressionType = GzipHandler.GZIP;
    }

    @BeforeEach
    public void setUp() throws Exception
    {
        testdir.ensureEmpty();

        File testFile = testdir.getPathFile("file.txt").toFile();
        try (OutputStream testOut = new BufferedOutputStream(new FileOutputStream(testFile)))
        {
            ByteArrayInputStream testIn = new ByteArrayInputStream(__content.getBytes("ISO8859_1"));
            IO.copy(testIn, testOut);
        }

        tester = new ServletTester("/context");
        tester.getContext().setResourceBase(testdir.getPath().toString());
        tester.getContext().addServlet(org.eclipse.jetty.servlet.DefaultServlet.class, "/");

        GzipHandler gzipHandler = new GzipHandler();
        tester.getContext().insertHandler(gzipHandler);
        tester.start();
    }

    @AfterEach
    public void tearDown() throws Exception
    {
        tester.stop();
    }

    @Test
    public void testGzip() throws Exception
    {
        // generated and parsed test

        ByteBuffer request = BufferUtil.toBuffer(
            "GET /context/file.txt HTTP/1.0\r\n" +
                "Host: tester\r\n" +
                "Accept-Encoding: " + compressionType + "\r\n" +
                "\r\n");

        HttpTester.Response response = HttpTester.parseResponse(tester.getResponses(request));

        assertEquals(HttpServletResponse.SC_OK, response.getStatus());
        assertEquals(compressionType, response.get("Content-Encoding"));

        InputStream testIn = null;
        ByteArrayInputStream compressedResponseStream = new ByteArrayInputStream(response.getContentBytes());
        if (compressionType.equals(GzipHandler.GZIP))
        {
            testIn = new GZIPInputStream(compressedResponseStream);
        }
        else if (compressionType.equals(GzipHandler.DEFLATE))
        {
            testIn = new InflaterInputStream(compressedResponseStream, new Inflater(true));
        }
        ByteArrayOutputStream testOut = new ByteArrayOutputStream();
        IO.copy(testIn, testOut);

        assertEquals(__content, testOut.toString("ISO8859_1"));
    }
}
