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

package org.eclipse.jetty.start;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;

import org.eclipse.jetty.toolchain.test.FS;
import org.eclipse.jetty.toolchain.test.IO;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;

public class TestEnv
{
    public static void copyTestDir(String testResourceDir, Path destDir) throws IOException
    {
        FS.ensureDirExists(destDir);
        File srcDir = MavenTestingUtils.getTestResourceDir(testResourceDir);
        IO.copyDir(srcDir, destDir.toFile());
    }

    public static void makeFile(Path dir, String relFilePath, String... contents) throws IOException
    {
        Path outputFile = dir.resolve(FS.separators(relFilePath));
        FS.ensureDirExists(outputFile.getParent());
        try (BufferedWriter writer = Files.newBufferedWriter(outputFile);
             PrintWriter out = new PrintWriter(writer))
        {
            for (String content : contents)
            {
                out.println(content);
            }
        }
    }
}
