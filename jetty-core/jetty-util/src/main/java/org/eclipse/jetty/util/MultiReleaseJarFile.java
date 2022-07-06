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

package org.eclipse.jetty.util;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.stream.Stream;

import org.eclipse.jetty.util.resource.Resource;

/**
 * <p>Utility class to handle a Multi Release Jar file</p>
 */
public class MultiReleaseJarFile implements Closeable
{
    private final Path jarFile;
    private final Resource.Mount jarResource;

    /**
     * Construct a multi release jar file for the current JVM version, ignoring directories.
     *
     * @param jarFile The file to open
     * @throws IOException if the jar file cannot be read
     */
    public MultiReleaseJarFile(Path jarFile) throws IOException
    {
        Objects.requireNonNull(jarFile, "Jar File");

        if (!Files.isRegularFile(jarFile))
            throw new IllegalArgumentException("Not a Jar File: " + jarFile);

        if (!Files.isRegularFile(jarFile))
            throw new IllegalArgumentException("Unable to read Jar File: " + jarFile);

        this.jarFile = jarFile;
        this.jarResource = Resource.newJarResource(jarFile);
    }

    /**
     * @return A stream of versioned entries from the jar, excluded any that are not applicable
     */
    @SuppressWarnings("resource")
    public Stream<Path> stream() throws IOException
    {
        Path rootPath = this.jarResource.root().getPath();

        return Files.walk(rootPath).filter((e) ->
        {
            // Skip entries that are not files.
            if (!Files.isRegularFile(e))
                return false;

            // Ignore META-INF
            return !e.getName(0).toString().equals("META-INF");
        });
    }

    /**
     * Get a versioned resource entry by name
     *
     * @param name The unversioned name of the resource
     * @return The versioned entry of the resource
     */
    public Path getEntry(String name) throws IOException
    {
        Path rootPath = this.jarResource.root().getPath();
        return rootPath.resolve(name);
    }

    @Override
    public void close() throws IOException
    {
        this.jarResource.close();
    }

    @Override
    public String toString()
    {
        return jarFile.toString();
    }
}
