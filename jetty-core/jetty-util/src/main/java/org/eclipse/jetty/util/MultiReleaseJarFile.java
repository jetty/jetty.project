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

        if (!Files.exists(jarFile))
            throw new IllegalArgumentException("File does not exist: " + jarFile);

        if (!Files.isRegularFile(jarFile))
            throw new IllegalArgumentException("Not a file: " + jarFile);

        // TODO : use FileID.isJar() in future PR
        if (!Resource.isArchive(jarFile))
            throw new IllegalArgumentException("Not a Jar: " + jarFile);

        if (!Files.isReadable(jarFile))
            throw new IllegalArgumentException("Unable to read Jar file: " + jarFile);

        this.jarFile = jarFile;
        this.jarResource = Resource.mountJar(jarFile);
        if (LOG.isDebugEnabled())
            LOG.debug("mounting {}", jarResource);
    }

    /**
     * Predicate to skip {@code module-info.class} files.
     *
     * <p>
     * This is a simple test against the last path segment using {@link Path#getFileName()}
     * </p>
     *
     * @param path the path to test
     * @return true if not a {@code module-info.class} file
     * TODO: move to FileID class in later PR
     */
    public static boolean skipModuleInfoClass(Path path)
    {
        Path filenameSegment = path.getFileName();
        if (filenameSegment == null)
            return true;

        return !filenameSegment.toString().equalsIgnoreCase("module-info.class");
    }

    /**
     * Predicate to skip {@code META-INF/versions/*} tree from walk/stream results.
     *
     * <p>
     * This only works with a zipfs based FileSystem
     * </p>
     *
     * @param path the path to test
     * @return true if not in {@code META-INF/versions/*} tree
     * TODO: move to FileID class in later PR
     */
    public static boolean skipMetaInfVersions(Path path)
    {
        return !isMetaInfVersions(path);
    }

    /**
     * Predicate to filter on {@code META-INF/versions/*} tree in walk/stream results.
     *
     * <p>
     * This only works with a zipfs based FileSystem
     * </p>
     *
     * @param path the path to test
     * @return true if path is in {@code META-INF/versions/*} tree
     * TODO: move to FileID class in later PR
     */
    public static boolean isMetaInfVersions(Path path)
    {
        if (path.getNameCount() < 3)
            return false;

        Path path0 = path.getName(0);
        Path path1 = path.getName(1);
        Path path2 = path.getName(2);

        return (path0.toString().equals("META-INF") &&
            path1.toString().equals("versions") &&
            path2.getFileName().toString().matches("[0-9]+"));
    }

    /**
     * Predicate to select all class files
     *
     * @param path the path to test
     * @return true if the filename ends with {@code .class}
     * TODO: move to FileID class in later PR
     */
    public static boolean isClassFile(Path path)
    {
        String filename = path.getFileName().toString();
        // has to end in ".class"
        if (!filename.toLowerCase(Locale.ENGLISH).endsWith(".class"))
            return false;
        // is it a valid class filename?
        int start = 0;
        int end = filename.length() - 6; // drop ".class"
        if (end <= start) // if the filename is only ".class"
            return false;
        // Test first character
        if (!Character.isJavaIdentifierStart(filename.charAt(0)))
            return false;
        // Test rest
        for (int i = start + 1; i < end; i++)
        {
            if (!Character.isJavaIdentifierPart(filename.codePointAt(i)))
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("Not a java identifier: {}", filename);
                return false;
            }
        }
        return true;
    }

    /**
     * Predicate useful for {@code Stream<Path>} to exclude hidden paths following
     * filesystem rules for hidden directories and files.
     *
     * @param base the base path to search from (anything above this path is not evaluated)
     * @param path the path to evaluate
     * @return true if hidden by FileSystem rules, false if not
     * @see Files#isHidden(Path)
     * TODO: move to FileID.isHidden(Path, Path)
     */
    public static boolean isHidden(Path base, Path path)
    {
        // Work with the path in relative form, from the base onwards to the path
        Path relative = base.relativize(path);

        int count = relative.getNameCount();
        for (int i = 0; i < count; i++)
        {
            try
            {
                if (Files.isHidden(relative.getName(i)))
                    return true;
            }
            catch (IOException ignore)
            {
                // ignore, if filesystem gives us an error, we cannot make the call on hidden status
            }
        }

        return false;
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
            .filter(MultiReleaseJarFile::skipMetaInfVersions);
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
