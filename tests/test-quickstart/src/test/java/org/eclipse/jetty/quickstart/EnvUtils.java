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

package org.eclipse.jetty.quickstart;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Common utility methods for quickstart tests
 */
public class EnvUtils
{
    /**
     * As the declared paths in this testcase might be actual paths on the system
     * running these tests, the expected paths should be cleaned up to represent
     * the actual system paths.
     * <p>
     * Eg: on fedora /etc/init.d is a symlink to /etc/rc.d/init.d
     */
    public static String toSystemPath(String rawpath)
    {
        Path path = FileSystems.getDefault().getPath(rawpath);
        if (Files.exists(path))
        {
            // It exists, resolve it to the real path
            try
            {
                path = path.toRealPath();
            }
            catch (IOException e)
            {
                // something prevented us from resolving to real path, fallback to
                // absolute path resolution (not as accurate)
                path = path.toAbsolutePath();
                e.printStackTrace();
            }
        }
        else
        {
            // File doesn't exist, resolve to absolute path
            // We can't rely on File.toCanonicalPath() here
            path = path.toAbsolutePath();
        }
        return path.toString();
    }

    public static void restoreSystemProperty(String key, String value)
    {
        if (value == null)
        {
            System.clearProperty(key);
        }
        else
        {
            System.setProperty(key, value);
        }
    }
}
