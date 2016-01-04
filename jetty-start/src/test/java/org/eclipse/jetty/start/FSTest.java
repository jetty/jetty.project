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
import java.util.List;

import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.junit.Assert;
import org.junit.Test;

public class FSTest
{
    @Test
    public void testCanReadDirectory()
    {
        File targetDir = MavenTestingUtils.getTargetDir();
        Assert.assertTrue("Can read dir: " + targetDir,FS.canReadDirectory(targetDir.toPath()));
    }

    @Test
    public void testCanReadDirectory_NotDir()
    {
        File bogusFile = MavenTestingUtils.getTestResourceFile("bogus.xml");
        Assert.assertFalse("Can read dir: " + bogusFile,FS.canReadDirectory(bogusFile.toPath()));
    }

    @Test
    public void testCanReadFile()
    {
        File pom = MavenTestingUtils.getProjectFile("pom.xml");
        Assert.assertTrue("Can read file: " + pom,FS.canReadFile(pom.toPath()));
    }
    
    /**
     * Utility method used by other test cases
     * @param expected the expected String paths to be converted (in place)
     */
    public static void toOsSeparators(List<String> expected)
    {
        for (int i = 0; i < expected.size(); i++)
        {
            String fixed = FS.separators(expected.get(i));
            expected.set(i,fixed);
        }
    }
}
