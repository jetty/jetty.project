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
import java.nio.file.Files;
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
    public static boolean isWebArchiveFile(File file)
    {
        return isWebArchiveFile(file.toPath());
    }

    public static boolean isWebArchiveFile(Path path)
    {
        if (!Files.isRegularFile(path))
            return false;

        String name = path.getFileName().toString().toLowerCase(Locale.ENGLISH);
        return (name.endsWith(".war") || name.endsWith(".jar"));
    }

    public static boolean isXmlFile(File path)
    {
        return isXmlFile(path.toPath());
    }

    public static boolean isXmlFile(Path path)
    {
        if (!Files.isRegularFile(path))
            return false;

        String name = path.getFileName().toString().toLowerCase(Locale.ENGLISH);
        return name.endsWith(".xml");
    }

    /**
     * Remove any 3 character suffix (e.g. ".war") from a path
     * @param path The string path
     * @return The path without the suffix or the original path
     */
    public static String getDot3Basename(String path)
    {
        if (path == null || path.length() <= 4 || path.charAt(path.length() - 4) != '.')
            return path;
        return path.substring(0, path.length() - 4);
    }
}
