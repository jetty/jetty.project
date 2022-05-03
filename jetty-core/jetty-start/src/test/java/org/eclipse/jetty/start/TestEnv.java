//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
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
