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
import java.util.Locale;
import java.util.Objects;
import java.util.stream.Stream;

import org.eclipse.jetty.util.resource.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p>Utility class to handle a Multi Release Jar file</p>
 */
public class MultiReleaseJarFile implements Closeable
{
    private static final Logger LOG = LoggerFactory.getLogger(MultiReleaseJarFile.class);

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
        this.jarResource = Resource.mountJar(jarFile);
        if (LOG.isDebugEnabled())
            LOG.debug("mounting {}", jarResource);
    }

    /**
     * Predicate to skip {@code module-info.class} files.
     * @param path the path to test
     * @return true if not a {@code module-info.class} file
     */
    public static boolean notModuleInfoClass(Path path)
    {
        return !path.getFileName().toString().equalsIgnoreCase("module-info.class");
    }

    /**
     * Predicate to skip {@code META-INF/versions/*} tree from walk/stream results
     * @param path the path to test
     * @return true if not in {@code META-INF/versions/*} tree
     */
    public static boolean notMetaInfVersions(Path path)
    {
        if (path.getNameCount() < 2)
            return false;
        Path path0 = path.getName(0);
        Path path1 = path.getName(1);

        return !(path0.toString().equals("META-INF") &&
            path1.toString().equals("versions"));
    }

    /**
     * Predicate to select all class files
     * @param path the path to test
     * @return true if the filename ends with {@code .class}
     */
    public static boolean isClassFile(Path path)
    {
        return path.getFileName().toString().toLowerCase(Locale.ENGLISH).endsWith(".class");
    }

    /**
     * @return A stream of versioned entries from the jar, excluding {@code META-INF/versions} entries.
     */
    @SuppressWarnings("resource")
    public Stream<Path> stream() throws IOException
    {
        Path rootPath = this.jarResource.root().getPath();

        return Files.walk(rootPath)
            // skip the entire META-INF/versions tree
            .filter(MultiReleaseJarFile::notMetaInfVersions);
    }

    /**
     * @return A stream of versioned class file entries from the jar, excluding {@code META-INF/versions} entries.
     */
    public Stream<Path> streamClasses() throws IOException
    {
        Path rootPath = this.jarResource.root().getPath();

        return Files.walk(rootPath)
            // Only interested in files (not directories)
            .filter(Files::isRegularFile)
            // Skip module-info classes
            .filter(MultiReleaseJarFile::notModuleInfoClass)
            // skip the entire META-INF/versions tree
            .filter(MultiReleaseJarFile::notMetaInfVersions)
            // only class files
            .filter(MultiReleaseJarFile::isClassFile);
    }

    @Override
    public void close() throws IOException
    {
        if (LOG.isDebugEnabled())
            LOG.debug("Closing {}", jarResource);
        this.jarResource.close();
    }

    @Override
    public String toString()
    {
        return jarFile.toString();
    }
}
