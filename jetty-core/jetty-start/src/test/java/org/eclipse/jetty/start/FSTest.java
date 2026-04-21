//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.eclipse.jetty.toolchain.test.MavenPaths;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDir;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDirExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(WorkDirExtension.class)
public class FSTest
{
    @Test
    public void testCanReadDirectory()
    {
        Path targetDir = MavenPaths.targetDir();
        assertTrue(FS.canReadDirectory(targetDir), "Can read dir: " + targetDir);
    }

    @Test
    public void testCanReadDirectoryNotDir()
    {
        Path bogusFile = MavenPaths.findTestResourceFile("bogus.xml");
        assertFalse(FS.canReadDirectory(bogusFile), "Can read dir: " + bogusFile);
    }

    @Test
    public void testCanReadFile()
    {
        Path pom = MavenPaths.projectFile("pom.xml");
        assertTrue(FS.canReadFile(pom), "Can read file: " + pom);
    }

    @Test
    public void testExtractEscaperZip(WorkDir workDir) throws IOException
    {
        Path dest = workDir.getEmptyPathDir();
        Path archive = MavenPaths.findTestResourceFile("bad-libs/escaper.zip");
        Path bad = Path.of("/tmp/evil.txt");
        Files.deleteIfExists(bad);
        assertThrows(IOException.class, () -> FS.extractZip(archive, dest));
        assertFalse(Files.exists(bad), "The escaper prevention didn't work, you should not have a /tmp/evil.txt file, but you do.");
    }
}
