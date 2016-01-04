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

import static org.hamcrest.Matchers.containsString;

import java.io.File;

import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.junit.Assert;
import org.junit.Test;

public class JarVersionTest
{
    private void assertJarVersion(String jarname, String expectedVersion)
    {
        File jarfile = MavenTestingUtils.getTestResourceFile(jarname);
        Assert.assertThat("Jar: " + jarname,JarVersion.getVersion(jarfile),containsString(expectedVersion));
    }

    @Test
    public void testNoManifestJar()
    {
        assertJarVersion("bad-libs/no-manifest.jar","(none specified)");
    }

    @Test
    public void testNotAJar()
    {
        assertJarVersion("bad-libs/not-a.jar","(error: ZipException ");
    }

    @Test
    public void testZeroLengthJar()
    {
        assertJarVersion("bad-libs/zero-length.jar","(error: ZipException ");
    }
}
