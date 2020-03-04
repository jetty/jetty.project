//
// ========================================================================
// Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under
// the terms of the Eclipse Public License 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0
//
// This Source Code may also be made available under the following
// Secondary Licenses when the conditions for such availability set
// forth in the Eclipse Public License, v. 2.0 are satisfied:
// the Apache License v2.0 which is available at
// https://www.apache.org/licenses/LICENSE-2.0
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.servlets;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;

import jakarta.servlet.ServletConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
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
        File testfile = new File(basedir, filename);
        PathAssert.assertFileExists("Content File should exist", testfile);
        return testfile;
    }

    protected byte[] loadContentFileBytes(final String fileName) throws IOException
    {
        String relPath = fileName;
        relPath = relPath.replaceFirst("^/context/", "");
        relPath = relPath.replaceFirst("^/", "");

        File contentFile = getTestFile(relPath);

        FileInputStream in = null;
        ByteArrayOutputStream out = null;
        try
        {
            in = new FileInputStream(contentFile);
            out = new ByteArrayOutputStream();
            IO.copy(in, out);
            return out.toByteArray();
        }
        finally
        {
            IO.close(out);
            IO.close(in);
        }
    }
}
