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

package org.eclipse.jetty.start;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;

import org.eclipse.jetty.toolchain.test.FS;
import org.eclipse.jetty.toolchain.test.IO;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.toolchain.test.OS;

public class TestEnv
{
    public static void copyTestDir(String testResourceDir, File destDir) throws IOException
    {
        FS.ensureDirExists(destDir);
        File srcDir = MavenTestingUtils.getTestResourceDir(testResourceDir);
        IO.copyDir(srcDir,destDir);
    }

    public static void makeFile(File dir, String relFilePath, String... contents) throws IOException
    {
        File outputFile = new File(dir,OS.separators(relFilePath));
        FS.ensureDirExists(outputFile.getParentFile());
        try (FileWriter writer = new FileWriter(outputFile); PrintWriter out = new PrintWriter(writer))
        {
            for (String content : contents)
            {
                out.println(content);
            }
        }
    }
}
