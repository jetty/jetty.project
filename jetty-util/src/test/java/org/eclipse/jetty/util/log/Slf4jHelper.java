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
