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

import java.io.File;
import java.util.List;

import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
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
