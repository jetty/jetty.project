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

import java.io.File;
import java.io.IOError;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URI;
import java.nio.channels.ReadableByteChannel;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.URIUtil;
import org.eclipse.jetty.util.paths.PathCollection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * PathCollection Resource.
 */
public class PathCollectionResource extends Resource
{
    private static final Logger LOG = LoggerFactory.getLogger(PathCollectionResource.class);
    private static final LinkOption[] NO_FOLLOW_LINKS = new LinkOption[]{LinkOption.NOFOLLOW_LINKS};
    private static final LinkOption[] FOLLOW_LINKS = new LinkOption[]{};

    private final PathCollection pathCollection;
    private final List<URI> uris;

    public PathCollectionResource(Path path)
    {
        this(PathCollection.from(path), List.of(path.toUri()));
    }

    /**
     * Construct a new PathCollectionResource from Path objects.
     *
     * @param paths the paths to use
     */
    public PathCollectionResource(List<Path> paths)
    {
        this(PathCollection.from(paths), paths.stream().map(Path::toUri).toList());
    }

    private PathCollectionResource(PathCollection pathCollection, List<URI> uris)
    {
        List<Path> absPaths = pathCollection.stream().map(path ->
        {
            try
            {
                assertValidPath(path);
                return path.toRealPath(NO_FOLLOW_LINKS);
            }
            catch (IOError | IOException e)
            {
                // Not able to resolve real/canonical path from provided path
                // This could be due to a glob reference, or a reference
                // to a path that doesn't exist (yet)
                if (LOG.isDebugEnabled())
                    LOG.debug("Unable to get real/canonical path for {}", path, e);
                return path;
            }
        }).toList();

        if (absPaths.size() != uris.size())
            throw new IllegalArgumentException("path collection size must be equal to uri list size");

        this.pathCollection = PathCollection.from(absPaths);
        this.uris = uris;
    }

    private PathCollectionResource(PathCollectionResource parent, String childSegment)
    {
        // Calculate the URI and the path separately, so that any aliasing done by
        // FileSystem.getPath(path,childPath) is visible as a difference to the URI
        // obtained via URIUtil.addDecodedPath(uri,childPath)
        this.pathCollection = PathCollection.from(parent.pathCollection.stream().map(path -> path.getFileSystem().getPath(path.toString(), childSegment)));
        String pathSegment = childSegment;
        if (isDirectory() && !pathSegment.endsWith("/"))
            pathSegment += "/";

        this.uris = new ArrayList<>();
        for (URI parentUri : parent.uris)
        {
            URI uri = URIUtil.addPath(parentUri, pathSegment);
            this.uris.add(uri);
        }
    }

    @Override
    public boolean isSame(Resource resource)
    {
        try
        {
            if (resource instanceof PathCollectionResource pathCollectionResource)
            {
                Iterator<Path> it1 = pathCollection.stream().iterator();
                Iterator<Path> it2 = pathCollectionResource.pathCollection.stream().iterator();

                while (it1.hasNext())
                {
                    if (!it2.hasNext())
                        return false;
                    Path p1 = it1.next();
                    Path p2 = it2.next();

                    if (!Files.isSameFile(p1, p2))
                        return false;
                }
                if (it2.hasNext())
                    return false;

                return true;
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
    public Resource addPath(String segment) throws IOException
    {
        // Check that the path is within the root,
        // but use the original path to create the
        // resource, to preserve aliasing.
        if (URIUtil.canonicalPath(segment) == null)
            throw new MalformedURLException(segment);

        if ("/".equals(segment))
            return this;

        return new PathCollectionResource(this, segment);
    }

    private void assertValidPath(Path path)
    {
        // TODO merged from 9.2, check if necessary
        String str = path.toString();
        int idx = StringUtil.indexOfControlChars(str);
        if (idx >= 0)
        {
            throw new InvalidPathException(str, "Invalid Character at index " + idx);
        }
    }

    @Override
    public void close()
    {
        pathCollection.close();
    }

    @Override
    public boolean delete() throws SecurityException
    {
        Iterator<Path> it = pathCollection.stream().iterator();
        while (it.hasNext())
        {
            Path path = it.next();
            try
            {
                if (!Files.deleteIfExists(path))
                    return false;
            }
            catch (IOException e)
            {
                LOG.trace("IGNORED", e);
                return false;
            }
        }
        return true;
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
        PathCollectionResource other = (PathCollectionResource)obj;
        return pathCollection.equals(other.pathCollection);
    }

    @Override
    public boolean exists()
    {
        Iterator<Path> it = pathCollection.stream().iterator();
        while (it.hasNext())
        {
            Path path = it.next();
            if (Files.exists(path, NO_FOLLOW_LINKS))
                return true;
        }
        return false;
    }

    @Override
    public File getFile() throws IOException
    {
        return null;
    }

    /**
     * @return the {@link Path} of the resource
     */
    public Path getPath()
    {
        return pathCollection.stream().findFirst().orElseThrow();
    }

    @Override
    public InputStream getInputStream() throws IOException
    {
        return Files.newInputStream(getPath(), StandardOpenOption.READ);
    }

    @Override
    public String getName()
    {
        return getPath().toAbsolutePath().toString();
    }

    @Override
    public ReadableByteChannel getReadableByteChannel() throws IOException
    {
        return Files.newByteChannel(getPath(), StandardOpenOption.READ);
    }

    @Override
    public URI getURI()
    {
        return this.getPath().toUri();
    }

    @Override
    public int hashCode()
    {
        final int prime = 31;
        int result = 1;
        result = (prime * result) + ((pathCollection == null) ? 0 : pathCollection.hashCode());
        return result;
    }

    @Override
    public boolean isDirectory()
    {
        return Files.isDirectory(getPath(), FOLLOW_LINKS);
    }

    @Override
    public long lastModified()
    {
        try
        {
            FileTime ft = Files.getLastModifiedTime(getPath(), FOLLOW_LINKS);
            return ft.toMillis();
        }
        catch (IOException e)
        {
            LOG.trace("IGNORED", e);
            return 0;
        }
    }

    @Override
    public long length()
    {
        try
        {
            return Files.size(getPath());
        }
        catch (IOException e)
        {
            // in case of error, use File.length logic of 0L
            return 0L;
        }
    }

    @Override
    public boolean isAlias()
    {
        return false;
    }

    @Override
    public String[] list()
    {
        List<String> allEntries = new ArrayList<>();
        Iterator<Path> it = pathCollection.stream().iterator();
        while (it.hasNext())
        {
            Path path = it.next();
            try (DirectoryStream<Path> dir = Files.newDirectoryStream(path))
            {
                List<String> entries = new ArrayList<>();
                for (Path entry : dir)
                {
                    String name = entry.getFileName().toString();

                    if (Files.isDirectory(entry))
                    {
                        name += "/";
                    }

                    entries.add(name);
                }
                allEntries.addAll(entries);
            }
            catch (DirectoryIteratorException e)
            {
                LOG.debug("Directory list failure", e);
                return null;
            }
            catch (IOException e)
            {
                LOG.debug("Directory list access failure", e);
                return null;
            }
        }
        return allEntries.toArray(new String[0]);
    }

    @Override
    public void copyTo(File destination) throws IOException
    {
        Path targetPath = destination.toPath();
        Iterator<Path> it = pathCollection.stream().iterator();
        while (it.hasNext())
        {
            Path sourcePath = it.next();
            Files.walkFileTree(sourcePath, new SimpleFileVisitor<>()
            {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException
                {
                    Path relative = sourcePath.relativize(dir);
                    Path target = targetPath.resolve(relative);
                    if (!Files.isDirectory(target))
                        Files.createDirectories(target);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException
                {
                    Path relative = sourcePath.relativize(file);
                    Path target = targetPath.resolve(relative);
                    Files.copy(file, target, StandardCopyOption.COPY_ATTRIBUTES);
                    return FileVisitResult.CONTINUE;
                }
            });
        }
    }

    @Override
    public String toString()
    {
        return this.pathCollection.toString();
    }
}
