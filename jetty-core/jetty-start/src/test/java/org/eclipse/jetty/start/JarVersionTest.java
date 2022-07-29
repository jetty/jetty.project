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

import java.nio.file.Path;

import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;

public class JarVersionTest
{
    private void assertJarVersion(String jarname, String expectedVersion)
    {
        Path jarfile = MavenTestingUtils.getTestResourcePathFile(jarname);
        assertThat("Jar: " + jarname, JarVersion.getVersion(jarfile), containsString(expectedVersion));
    }

    @Test
    public void testNoManifestJar()
    {
        assertJarVersion("bad-libs/no-manifest.jar", "(none specified)");
    }

    @Test
    public void testNotAJar()
    {
        assertJarVersion("bad-libs/not-a.jar", "(error: ZipException ");
    }

    @Test
    public void testZeroLengthJar()
    {
        assertJarVersion("bad-libs/zero-length.jar", "(error: ZipException ");
    }
}
