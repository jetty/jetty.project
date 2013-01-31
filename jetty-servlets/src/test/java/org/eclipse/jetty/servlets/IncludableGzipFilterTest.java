//
//  ========================================================================
//  Copyright (c) 1995-2013 Mort Bay Consulting Pty. Ltd.
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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Collection;
import java.util.zip.GZIPInputStream;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;

import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.io.ByteArrayBuffer;
import org.eclipse.jetty.servlet.FilterHolder;
import org.eclipse.jetty.testing.HttpTester;
import org.eclipse.jetty.testing.ServletTester;
import org.eclipse.jetty.toolchain.test.TestingDir;
import org.eclipse.jetty.util.IO;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

@RunWith(Parameterized.class)
public class IncludableGzipFilterTest
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
    
    @Rule
    public TestingDir testdir = new TestingDir();
    
    private static String __content =
        "Lorem ipsum dolor sit amet, consectetur adipiscing elit. In quis felis nunc. "+
        "Quisque suscipit mauris et ante auctor ornare rhoncus lacus aliquet. Pellentesque "+
        "habitant morbi tristique senectus et netus et malesuada fames ac turpis egestas. "+
        "Vestibulum sit amet felis augue, vel convallis dolor. Cras accumsan vehicula diam "+
        "at faucibus. Etiam in urna turpis, sed congue mi. Morbi et lorem eros. Donec vulputate "+
        "velit in risus suscipit lobortis. Aliquam id urna orci, nec sollicitudin ipsum. "+
        "Cras a orci turpis. Donec suscipit vulputate cursus. Mauris nunc tellus, fermentum "+
        "eu auctor ut, mollis at diam. Quisque porttitor ultrices metus, vitae tincidunt massa "+
        "sollicitudin a. Vivamus porttitor libero eget purus hendrerit cursus. Integer aliquam "+
        "consequat mauris quis luctus. Cras enim nibh, dignissim eu faucibus ac, mollis nec neque. "+
        "Aliquam purus mauris, consectetur nec convallis lacinia, porta sed ante. Suspendisse "+
        "et cursus magna. Donec orci enim, molestie a lobortis eu, imperdiet vitae neque.";

    private ServletTester tester;
    private String compressionType;
    
    public IncludableGzipFilterTest(String compressionType)
    {
        this.compressionType = compressionType;
    }
    
    @Before
    public void setUp() throws Exception
    {
        testdir.ensureEmpty();

        File testFile = testdir.getFile("file.txt");
        BufferedOutputStream testOut = new BufferedOutputStream(new FileOutputStream(testFile));
        ByteArrayInputStream testIn = new ByteArrayInputStream(__content.getBytes("ISO8859_1"));
        IO.copy(testIn,testOut);
        testOut.close();
        
        tester=new ServletTester();
        tester.setContextPath("/context");
        tester.setResourceBase(testdir.getDir().getCanonicalPath());
        tester.addServlet(org.eclipse.jetty.servlet.DefaultServlet.class, "/");
        FilterHolder holder = tester.addFilter(IncludableGzipFilter.class,"/*",null);
        holder.setInitParameter("mimeTypes","text/plain");
        tester.start();
    }

    @After
    public void tearDown() throws Exception
    {
        tester.stop();
        IO.delete(testdir.getDir());
    }

    @Test
    public void testGzipFilter() throws Exception
    {
        // generated and parsed test
        HttpTester request = new HttpTester();
        HttpTester response = new HttpTester();

        request.setMethod("GET");
        request.setVersion("HTTP/1.0");
        request.setHeader("Host","tester");
        request.setHeader("accept-encoding", compressionType);
        request.setURI("/context/file.txt");
        
        ByteArrayBuffer reqsBuff = new ByteArrayBuffer(request.generate().getBytes());
        ByteArrayBuffer respBuff = tester.getResponses(reqsBuff);
        response.parse(respBuff.asArray());
                
        assertTrue(response.getMethod()==null);
        assertTrue(response.getHeader("Content-Encoding").equalsIgnoreCase(compressionType));
        assertEquals(HttpServletResponse.SC_OK,response.getStatus());
        
        InputStream testIn = null;
        ByteArrayInputStream compressedResponseStream = new ByteArrayInputStream(response.getContentBytes());
        if (compressionType.equals(GzipFilter.GZIP))
        {
            testIn = new GZIPInputStream(compressedResponseStream);
        }
        else if (compressionType.equals(GzipFilter.DEFLATE))
        {
            testIn = new InflaterInputStream(compressedResponseStream, new Inflater(true));
        }
        ByteArrayOutputStream testOut = new ByteArrayOutputStream();
        IO.copy(testIn,testOut);
        
        assertEquals(__content, testOut.toString("ISO8859_1"));
    }
}
