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

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.FileSystem;
import java.nio.file.FileSystemNotFoundException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

public class TestEEResources
{
    public static final String CONTEXT_RESOURCES = "/contextResources";

    public static URL getResource(String name)
    {
        return TestEEResources.class.getResource(name);
    }

    public static InputStream getResourceAsStream(String name)
    {
        return TestEEResources.class.getResourceAsStream(name);
    }

    public static Path getResourceAsPath(String name)
    {
        URL url = TestEEResources.class.getResource(name);
        if (url == null)
            return null;
        try
        {
            URI uri = url.toURI();
            // Handle resources from JAR
            if ("jar".equals(uri.getScheme()))
            {
                try
                {
                    FileSystem fileSystem;
                    try
                    {
                        fileSystem = FileSystems.getFileSystem(uri);
                    }
                    catch (FileSystemNotFoundException e)
                    {
                        // Since this is for testing, this file system is never closed and lives as longs as the JVM.
                        fileSystem = FileSystems.newFileSystem(uri, Map.of());
                    }
                    String fullPath = uri.toString();
                    String resourcePath = fullPath.substring(fullPath.indexOf("!/") + 2);
                    return fileSystem.getPath(resourcePath).toAbsolutePath();
                }
                catch (IOException e)
                {
                    throw new RuntimeException("Failed to access JAR resource", e);
                }
            }
            return Paths.get(uri);
        }
        catch (URISyntaxException e)
        {
            throw new RuntimeException(e);
        }
    }

    public static Path getResourceAsPathDir(String name)
    {
        Path path = getResourceAsPath(name);
        assert path == null || Files.isDirectory(path);
        return path;
    }
}
