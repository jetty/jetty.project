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

package org.eclipse.jetty.util.log;

import java.io.File;
import java.io.FileFilter;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;

import org.eclipse.jetty.toolchain.test.MavenTestingUtils;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

public final class Slf4jHelper
{
    public static ClassLoader createTestClassLoader(ClassLoader parentClassLoader) throws MalformedURLException
    {
        File testJarDir = MavenTestingUtils.getTargetFile("test-jars");
        assumeTrue(testJarDir.exists()); // trigger @Ignore if dir not there

        File[] jarfiles = testJarDir.listFiles(new FileFilter()
        {
            public boolean accept(File path)
            {
                if (!path.isFile())
                {
                    return false;
                }
                return path.getName().endsWith(".jar");
            }
        });

        assumeTrue(jarfiles.length > 0); // trigger @Ignore if no jar files.

        URL[] urls = new URL[jarfiles.length];
        for (int i = 0; i < jarfiles.length; i++)
        {
            urls[i] = jarfiles[i].toURI().toURL();
            // System.out.println("Adding test-jar => " + urls[i]);
        }

        return new URLClassLoader(urls, parentClassLoader);
    }
}
