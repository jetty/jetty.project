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
import java.nio.file.Path;
import java.util.Locale;

/**
 * Simple, yet surprisingly common utility methods for identifying various file types commonly seen and worked with in a
 * deployment scenario.
 */
public class FileID
{
    /**
     * Is the path a Web Archive File (not directory)
     *
     * @param file the path to test.
     * @return True if a .war or .jar file.
     */
    public static boolean isWebArchive(File file)
    {
        return isWebArchive(file.getName());
    }

    /**
     * Is the path a Web Archive File (not directory)
     *
     * @param path the path to test.
     * @return True if a .war or .jar file.
     */
    public static boolean isWebArchive(Path path)
    {
        return isWebArchive(path.getFileName().toString());
    }

    /**
     * Is the filename a WAR file.
     *
     * @param filename the filename to test.
     * @return True if a .war or .jar file.
     */
    public static boolean isWebArchive(String filename)
    {
        String name = filename.toLowerCase(Locale.ENGLISH);
        return (name.endsWith(".war"));
    }

    public static boolean isXml(File path)
    {
        return isXml(path.getName());
    }

    public static boolean isXml(Path path)
    {
        return isXml(path.getFileName().toString());
    }

    public static boolean isXml(String filename)
    {
        return filename.toLowerCase(Locale.ENGLISH).endsWith(".xml");
    }

    /**
     * Retrieve the basename of a path. This is the name of the
     * last segment of the path, with any dot suffix (e.g. ".war") removed
     * @param path The string path
     * @return The last segment of the path without any dot suffix
     */
    public static String getBasename(Path path)
    {
        String basename = path.getFileName().toString();
        int dot = basename.lastIndexOf('.');
        if (dot >= 0)
            basename = basename.substring(0, dot);
        return basename;
    }
}
