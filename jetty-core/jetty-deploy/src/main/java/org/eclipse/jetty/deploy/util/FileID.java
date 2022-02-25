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

package org.eclipse.jetty.deploy.util;

import java.io.File;
import java.util.Locale;

/**
 * Simple, yet surprisingly common utility methods for identifying various file types commonly seen and worked with in a
 * deployment scenario.
 */
public class FileID
{
    /**
     * Is the path a Web Archive?
     *
     * @param path the path to test.
     * @return True if a .war or .jar or exploded web directory
     * @see FileID#isWebArchiveFile(File)
     */
    public static boolean isWebArchive(File path)
    {
        if (path.isFile())
        {
            String name = path.getName().toLowerCase(Locale.ENGLISH);
            return (name.endsWith(".war") || name.endsWith(".jar"));
        }

        File webInf = new File(path, "WEB-INF");
        File webXml = new File(webInf, "web.xml");
        return webXml.exists() && webXml.isFile();
    }

    /**
     * Is the path a Web Archive File (not directory)
     *
     * @param path the path to test.
     * @return True if a .war or .jar file.
     * @see FileID#isWebArchive(File)
     */
    public static boolean isWebArchiveFile(File path)
    {
        if (!path.isFile())
        {
            return false;
        }

        String name = path.getName().toLowerCase(Locale.ENGLISH);
        return (name.endsWith(".war") || name.endsWith(".jar"));
    }

    public static boolean isXmlFile(File path)
    {
        if (!path.isFile())
        {
            return false;
        }

        String name = path.getName().toLowerCase(Locale.ENGLISH);
        return name.endsWith(".xml");
    }
}
