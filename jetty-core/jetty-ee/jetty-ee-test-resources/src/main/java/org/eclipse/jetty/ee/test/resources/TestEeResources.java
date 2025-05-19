//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.ee.test.resources;

import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class TestEeResources
{
    public static URL getResource(String name)
    {
        return TestEeResources.class.getResource(name);
    }

    public static InputStream getResourceAsStream(String name)
    {
        return TestEeResources.class.getResourceAsStream(name);
    }

    public static Path getResourceAsPath(String name)
    {
        URL url = TestEeResources.class.getResource(name);
        return url == null ? null : Paths.get(url.getPath());
    }

    public static Path getResourceAsPathDir(String name)
    {
        Path path = getResourceAsPath(name);
        assert path == null || Files.isDirectory(path);
        return path;
    }
}
