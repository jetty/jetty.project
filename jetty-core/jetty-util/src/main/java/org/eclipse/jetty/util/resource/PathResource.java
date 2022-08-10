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

package org.eclipse.jetty.util.resource;

import java.io.IOException;
import java.net.URI;
import java.nio.file.FileSystemNotFoundException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;

import org.eclipse.jetty.util.Index;
import org.eclipse.jetty.util.URIUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Java NIO Path Resource.
 */
public class PathResource extends Resource
{
    private static final Logger LOG = LoggerFactory.getLogger(PathResource.class);

    public static Index<String> ALLOWED_SCHEMES = new Index.Builder<String>()
        .caseSensitive(false)
        .with("file")
        .with("jrt")
        .build();

    private final Path path;
    private final Path alias;
    private final URI uri;

    private Path checkAliasPath()
    {
        Path abs = path;

        /* Catch situation where the Path class has already normalized
         * the URI eg. input path "aa./foo.txt"
         * from an #resolve(String) is normalized away during
         * the creation of a Path object reference.
         * If the URI is different from the Path.toUri() then
         * we will just use the original URI to construct the
         * alias reference Path.
         *
         * ZipFS has lots of restrictions, you cannot trigger many of the alias behavior
         * like we can in Windows or OSX.
         */
        if (!URIUtil.equalsIgnoreEncodings(uri, cleanUri(path)))
        {
            try
            {
                return Paths.get(uri).toRealPath();
            }
            catch (IOException ioe)
            {
                // If the toRealPath() call fails, then let
                // the alias checking routines continue on
                // to other techniques.
                LOG.trace("IGNORED", ioe);
            }
        }

        if (!abs.isAbsolute())
            abs = path.toAbsolutePath();

        // Any normalization difference means it's an alias,
        // and we don't want to bother further to follow
        // symlinks as it's an alias anyway.
        Path normal = path.normalize();
        if (!isSameName(abs, normal))
            return normal;

        try
        {
            if (Files.isSymbolicLink(path))
                return path.getParent().resolve(Files.readSymbolicLink(path));

            if (Files.exists(path))
            {
                Path real = abs.toRealPath();
                if (!isSameName(abs, real))
                    return real;
            }
        }
        catch (IOException e)
        {
            LOG.trace("IGNORED", e);
        }
        catch (Exception e)
        {
            LOG.warn("bad alias ({} {}) for {}", e.getClass().getName(), e.getMessage(), path);
        }
        return null;
    }

    /**
     * Test if the paths are the same name.
     *
     * <p>
     * If the real path is not the same as the absolute path
     * then we know that the real path is the alias for the
     * provided path.
     * </p>
     *
     * <p>
     * For OS's that are case insensitive, this should
     * return the real (on-disk / case correct) version
     * of the path.
     * </p>
     *
     * <p>
     * We have to be careful on Windows and OSX.
     * </p>
     *
     * <p>
     * Assume we have the following scenario:
     * </p>
     *
     * <pre>
     *   Path a = new File("foo").toPath();
     *   Files.createFile(a);
     *   Path b = new File("FOO").toPath();
     * </pre>
     *
     * <p>
     * There now exists a file called {@code foo} on disk.
     * Using Windows or OSX, with a Path reference of
     * {@code FOO}, {@code Foo}, {@code fOO}, etc.. means the following
     * </p>
     *
     * <pre>
     *                        |  OSX    |  Windows   |  Linux
     * -----------------------+---------+------------+---------
     * Files.exists(a)        |  True   |  True      |  True
     * Files.exists(b)        |  True   |  True      |  False
     * Files.isSameFile(a,b)  |  True   |  True      |  False
     * a.equals(b)            |  False  |  True      |  False
     * </pre>
     *
     * <p>
     * See the javadoc for Path.equals() for details about this FileSystem
     * behavior difference
     * </p>
     *
     * <p>
     * We also cannot rely on a.compareTo(b) as this is roughly equivalent
     * in implementation to a.equals(b)
     * </p>
     */
    public static boolean isSameName(Path pathA, Path pathB)
    {
        int aCount = pathA.getNameCount();
        int bCount = pathB.getNameCount();
        if (aCount != bCount)
        {
            // different number of segments
            return false;
        }

        // compare each segment of path, backwards
        for (int i = bCount; i-- > 0; )
        {
            if (!pathA.getName(i).toString().equals(pathB.getName(i).toString()))
            {
                return false;
            }
        }

        return true;
    }

    /**
     * Construct a new PathResource from a URI object.
     * <p>
     * Must be an absolute URI using the <code>file</code> scheme.
     *
     * @param uri the URI to build this PathResource from.
     * @throws IOException if unable to construct the PathResource from the URI.
     */
    PathResource(URI uri)
    {
        this(uri, false);
    }

    PathResource(URI uri, boolean bypassAllowedSchemeCheck)
    {
        if (!uri.isAbsolute())
            throw new IllegalArgumentException("not an absolute uri: " + uri);
        if (!bypassAllowedSchemeCheck && !ALLOWED_SCHEMES.contains(uri.getScheme()))
            throw new IllegalArgumentException("not an allowed scheme: " + uri);

        try
        {
            this.path = Paths.get(uri);
            this.uri = cleanUri(this.path);
            this.alias = checkAliasPath();
        }
        catch (FileSystemNotFoundException e)
        {
            throw new IllegalStateException("No FileSystem mounted for : " + uri, e);
        }
    }

    @Override
    public boolean isSame(Resource resource)
    {
        try
        {
            if (resource instanceof PathResource)
            {
                Path path = resource.getPath();
                return Files.isSameFile(getPath(), path);
            }
        }
        catch (IOException e)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("ignored", e);
        }
        return false;
    }

    @Override
    public boolean equals(Object obj)
    {
        if (this == obj)
        {
            return true;
        }
        if (obj == null)
        {
            return false;
        }
        if (getClass() != obj.getClass())
        {
            return false;
        }
        PathResource other = (PathResource)obj;
        if (path == null)
        {
            if (other.path != null)
            {
                return false;
            }
        }
        else if (!path.equals(other.path))
        {
            return false;
        }
        return true;
    }

    /**
     * @return the {@link Path} of the resource
     */
    public Path getPath()
    {
        return path;
    }

    @Override
    public String getName()
    {
        return path.toAbsolutePath().toString();
    }

    @Override
    public boolean isMemoryMappable()
    {
        return "file".equalsIgnoreCase(uri.getScheme());
    }

    @Override
    public URI getURI()
    {
        return this.uri;
    }

    @Override
    public int hashCode()
    {
        final int prime = 31;
        int result = 1;
        result = (prime * result) + ((path == null) ? 0 : path.hashCode());
        return result;
    }

    @Override
    public boolean isContainedIn(Resource r)
    {
        return r.getClass() == PathResource.class && path.startsWith(r.getPath());
    }

    @Override
    public boolean isAlias()
    {
        return this.alias != null;
    }

    /**
     * The Alias as a Path.
     * <p>
     * Note: this cannot return the alias as a DIFFERENT path in 100% of situations,
     * due to Java's internal Path/File normalization.
     * </p>
     *
     * @return the alias as a path.
     */
    public Path getAliasPath()
    {
        return this.alias;
    }

    @Override
    public URI getAlias()
    {
        return this.alias == null ? null : this.alias.toUri();
    }

    @Override
    public void copyTo(Path destination) throws IOException
    {
        // TODO reconcile this impl with super's
        if (isDirectory())
            Files.walkFileTree(this.path, new TreeCopyFileVisitor(this.path, destination));
        else
            Files.copy(this.path, destination);
    }

    private static URI cleanUri(Path path)
    {
        String raw = URIUtil.correctFileURI(path.toUri()).toASCIIString();

        if (Files.isDirectory(path) && !raw.endsWith("/"))
            raw += '/';
        return URI.create(raw);
    }

    @Override
    public String toString()
    {
        return this.uri.toASCIIString();
    }

    private static class TreeCopyFileVisitor extends SimpleFileVisitor<Path>
    {
        private final String relativeTo;
        private final Path target;

        public TreeCopyFileVisitor(Path relativeTo, Path target)
        {
            this.relativeTo = relativeTo.getRoot().relativize(relativeTo).toString();
            this.target = target;
        }

        @Override
        public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException
        {
            Path resolvedTarget = target.resolve(dir.getRoot().resolve(relativeTo).relativize(dir).toString());
            if (Files.notExists(resolvedTarget))
                Files.createDirectories(resolvedTarget);
            return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException
        {
            Path resolvedTarget = target.resolve(file.getRoot().resolve(relativeTo).relativize(file).toString());
            Files.copy(file, resolvedTarget, StandardCopyOption.REPLACE_EXISTING);
            return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult visitFileFailed(Path file, IOException exc)
        {
            return FileVisitResult.CONTINUE;
        }
    }
}
