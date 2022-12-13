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

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.eclipse.jetty.toolchain.test.MavenPaths;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDir;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class FSTest
{
    @Test
    public void testCanReadDirectory()
    {
        File targetDir = MavenTestingUtils.getTargetDir();
        assertTrue(FS.canReadDirectory(targetDir.toPath()), "Can read dir: " + targetDir);
    }

    @Test
    public void testCanReadDirectoryNotDir()
    {
        File bogusFile = MavenTestingUtils.getTestResourceFile("bogus.xml");
        assertFalse(FS.canReadDirectory(bogusFile.toPath()), "Can read dir: " + bogusFile);
    }

    @Test
    public void testCanReadFile()
    {
        File pom = MavenTestingUtils.getProjectFile("pom.xml");
        assertTrue(FS.canReadFile(pom.toPath()), "Can read file: " + pom);
    }

    @Test
    public void testExtractEscaperZip(WorkDir workDir) throws IOException
    {
        Path archive = MavenPaths.findTestResourceFile("bad-libs/escaper.zip");
        Path dest = workDir.getEmptyPathDir();
        Path bad = Path.of("/tmp/evil.txt");
        Files.deleteIfExists(bad);
        assertThrows(IOException.class, () -> FS.extractZip(archive, dest));
        assertFalse(Files.exists(bad), "The escaper prevention didn't work, you should not have a /tmp/evil.txt file, but you do.");
    }

    /**
     * Utility method used by other test cases
     *
     * @param expected the expected String paths to be converted (in place)
     */
    public static void toFsSeparators(List<String> expected)
    {
        for (int i = 0; i < expected.size(); i++)
        {
            String fixed = FS.separators(expected.get(i));
            expected.set(i, fixed);
        }
    }
}
