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
import java.nio.file.DirectoryIteratorException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

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
    private final URI uri;
    private boolean targetResolved = false;
    private Path targetPath;

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
     */
    PathResource(URI uri)
    {
        this(uri, false);
    }

    PathResource(URI uri, boolean bypassAllowedSchemeCheck)
    {
        // normalize to referenced location, Paths.get() doesn't like "/bar/../foo/text.txt" style references
        // and will return a Path that will not be found with `Files.exists()` or `Files.isDirectory()`
        this(Paths.get(uri.normalize()), uri, bypassAllowedSchemeCheck);
    }

    PathResource(Path path)
    {
        this(path, path.toUri(), true);
    }

    PathResource(Path path, URI uri, boolean bypassAllowedSchemeCheck)
    {
        if (!uri.isAbsolute())
            throw new IllegalArgumentException("not an absolute uri: " + uri);
        if (!bypassAllowedSchemeCheck && !ALLOWED_SCHEMES.contains(uri.getScheme()))
            throw new IllegalArgumentException("not an allowed scheme: " + uri);

        String uriString = uri.toASCIIString();
        if (Files.isDirectory(path) && !uriString.endsWith(URIUtil.SLASH))
            uri = URIUtil.correctFileURI(URI.create(uriString + URIUtil.SLASH));

        this.path = path;
        this.uri = uri;
    }

    @Override
    public boolean exists()
    {
        return Files.exists(targetPath != null ? targetPath : path);
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
        return Objects.equals(path, other.path);
    }

    /**
     * @return the {@link Path} of the resource
     */
    public Path getPath()
    {
        return path;
    }

    public List<Resource> list()
    {
        if (!isDirectory())
            return List.of(); // empty

        try (Stream<Path> dirStream = Files.list(getPath()))
        {
            return dirStream.map(PathResource::new).collect(Collectors.toCollection(ArrayList::new));
        }
        catch (DirectoryIteratorException e)
        {
            LOG.debug("Directory list failure", e);
        }
        catch (IOException e)
        {
            LOG.debug("Directory list access failure", e);
        }
        return List.of(); // empty
    }

    @Override
    public String getName()
    {
        return path.toAbsolutePath().toString();
    }

    @Override
    public String getFileName()
    {
        Path fn = path.getFileName();
        if (fn == null) // if path has no segments (eg "/")
            return "";
        return fn.toString();
    }

    @Override
    public URI getURI()
    {
        return this.uri;
    }

    @Override
    public int hashCode()
    {
        return Objects.hashCode(path);
    }

    @Override
    public boolean isContainedIn(Resource r)
    {
        return r.getClass() == PathResource.class && path.startsWith(r.getPath());
    }

    @Override
    public URI getTargetURI()
    {
        if (!targetResolved)
        {
            targetPath = resolveTargetPath();
            targetResolved = true;
        }
        if (targetPath == null)
            return null;
        return targetPath.toUri();
    }

    private Path resolveTargetPath()
    {
        // If the path doesn't exist, then there's no alias to reference
        if (!Files.exists(path))
            return null;

        try
        {
            Path real = path.toRealPath();
            if (!isSameName(path, real))
                return real;
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

    @Override
    public void copyTo(Path destination) throws IOException
    {
        // TODO reconcile this impl with super's
        if (isDirectory())
            Files.walkFileTree(this.path, new TreeCopyFileVisitor(this.path, destination));
        else
            Files.copy(this.path, destination);
    }

    /**
     * Ensure Path to URI is sane when it returns a directory reference.
     *
     * <p>
     *     This is different than {@link Path#toUri()} in that not
     *     all FileSystems seem to put the trailing slash on a directory
     *     reference in the URI.
     * </p>
     *
     * @param path the path to convert to URI
     * @return the appropriate URI for the path
     */
    private static URI toUri(Path path)
    {
        URI pathUri = path.toUri();
        String rawUri = path.toUri().toASCIIString();

        if (Files.isDirectory(path) && !rawUri.endsWith("/"))
        {
            return URI.create(rawUri + '/');
        }
        return pathUri;
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
