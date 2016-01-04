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

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;

import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;

import org.eclipse.jetty.toolchain.test.PathAssert;
import org.eclipse.jetty.util.IO;

@SuppressWarnings("serial")
public class TestDirContentServlet extends HttpServlet
{
    private File basedir;

    @Override
    public void init(ServletConfig config) throws ServletException
    {
        basedir = new File(config.getInitParameter("baseDir"));
    }

    public File getTestFile(String filename)
    {
        File testfile = new File(basedir,filename);
        PathAssert.assertFileExists("Content File should exist",testfile);
        return testfile;
    }

    protected byte[] loadContentFileBytes(final String fileName) throws IOException
    {
        String relPath = fileName;
        relPath = relPath.replaceFirst("^/context/","");
        relPath = relPath.replaceFirst("^/","");

        File contentFile =  getTestFile(relPath);

        FileInputStream in = null;
        ByteArrayOutputStream out = null;
        try
        {
            in = new FileInputStream(contentFile);
            out = new ByteArrayOutputStream();
            IO.copy(in,out);
            return out.toByteArray();
        }
        finally
        {
            IO.close(out);
            IO.close(in);
        }
    }
}
