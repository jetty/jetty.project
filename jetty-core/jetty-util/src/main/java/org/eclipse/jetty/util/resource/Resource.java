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

package org.eclipse.jetty.util.resource;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.channels.ReadableByteChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.stream.Stream;

import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.URIUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p>
 * A Resource is a wrapper over a {@link Path} object pointing to a file or directory that can be represented by a{@link java.nio.file.FileSystem}.
 * </p>
 * <p>
 * Supports real filesystems, and also <a href="https://docs.oracle.com/en/java/javase/17/docs/api/jdk.zipfs/module-summary.html">ZipFS</a>.
 * </p>
 */
public abstract class Resource implements Iterable<Resource>
{
    private static final Logger LOG = LoggerFactory.getLogger(Resource.class);
    private static final LinkOption[] NO_FOLLOW_LINKS = new LinkOption[]{LinkOption.NOFOLLOW_LINKS};

    public static String dump(Resource resource)
    {
        if (resource == null)
            return "null exists=false directory=false lm=-1";
        return "%s exists=%b directory=%b lm=%s"
            .formatted(resource.toString(), resource.exists(), resource.isDirectory(), resource.lastModified());
    }

    /**
     * Return the Path corresponding to this resource.
     *
     * @return the path or null if there is no Path representation.
     */
    public abstract Path getPath();

    /**
     * Return true if this resource is contained in the Resource r, either because
     * r is a folder or a jar file or any form of resource capable of containing other resources.
     *
     * @param container the containing resource
     * @return true if this Resource is contained, false otherwise
     * @see #contains(Resource)
     */
    public boolean isContainedIn(Resource container)
    {
        return container != null && container.contains(this);
    }

    /**
     * Return true if this resource deeply contains the other Resource.  This resource must be
     * a directory or a jar file or any form of resource capable of containing other resources.
     *
     * @param other the resource
     * @return true if this Resource is deeply contains the other Resource, false otherwise
     * @see #isContainedIn(Resource)
     */
    public boolean contains(Resource other)
    {
        if (other == null)
            return false;

        URI thisURI = getURI();
        if (thisURI == null)
            throw new UnsupportedOperationException("Resources without a URI must implement contains");

        URI otherURI = other.getURI();
        if (otherURI == null)
            return false;

        // Different schemes? not a chance it contains the other
        if (!StringUtil.asciiEqualsIgnoreCase(thisURI.getScheme(), otherURI.getScheme()))
            return false;

        // Different authorities? not a valid contains() check
        if (!Objects.equals(thisURI.getAuthority(), otherURI.getAuthority()))
            return false;

        // Ensure that if `file` scheme is used, it's using a consistent convention to allow for startsWith check
        String thisURIString = URIUtil.correctFileURI(thisURI).toASCIIString();
        String otherURIString = URIUtil.correctFileURI(otherURI).toASCIIString();

        return otherURIString.startsWith(thisURIString) &&
            (thisURIString.length() == otherURIString.length() || otherURIString.charAt(thisURIString.length()) == '/');
    }

    /**
     * Get the relative path from this Resource to a possibly contained resource.
     * @param other The other resource that may be contained in this resource
     * @return a relative Path representing the path from this resource to the other resource,
     *   or null if not able to represent other resources as relative to this resource
     */
    public Path getPathTo(Resource other)
    {
        Path thisPath = getPath();
        if (thisPath == null)
            throw new UnsupportedOperationException("Resources without a Path must implement getPathTo");

        if (!contains(other))
            return null;

        Path otherPath = other.getPath();
        if (otherPath == null)
            return null;

        return thisPath.relativize(otherPath);
    }

    /**
     * <p>Return an Iterator of all Resource's referenced in this Resource.</p>
     * <p>This is meaningful if you have a {@link CombinedResource},
     * otherwise it will be a single entry Iterator of this resource.</p>
     *
     * @return the iterator of Resources.
     */
    @Override
    public Iterator<Resource> iterator()
    {
        return List.of(this).iterator();
    }

    /**
     * Equivalent to {@link Files#exists(Path, LinkOption...)} with the following parameters:
     * {@link #getPath()} and {@link LinkOption#NOFOLLOW_LINKS}.
     *
     * @return true if the represented resource exists.
     */
    public boolean exists()
    {
        return Files.exists(getPath(), NO_FOLLOW_LINKS);
    }

    /**
     * Return true if resource represents a directory of potential resources.
     *
     * @return true if the represented resource is a container/directory.
     */
    public abstract boolean isDirectory();

    /**
     * True if the resource is readable.
     *
     * @return true if the represented resource exists, and can read from.
     */
    public abstract boolean isReadable();

    /**
     * The time the resource was last modified.
     *
     * @return the last modified time instant, or {@link Instant#EPOCH} if unable to obtain last modified.
     */
    public Instant lastModified()
    {
        return Instant.EPOCH;
    }

    /**
     * Length of the resource.
     *
     * @return the length of the resource in bytes, or -1L if unable to provide a size (such as a directory resource).
     */
    public long length()
    {
        return -1L;
    }

    /**
     * URI representing the resource.
     *
     * @return a URI representing the given resource, or null if there is no URI representation of the resource.
     */
    public abstract URI getURI();

    /**
     * The full name of the resource.
     *
     * @return the full name of the resource, or null if there is no name for the resource.
     */
    public abstract String getName();

    /**
     * <p>The file name of the resource.</p>
     *
     * <p>This is the last segment of the path.</p>
     *
     * @return the filename of the resource, or "" if there are no path segments (eg: path of "/"), or null if resource
     *         cannot determine a filename.
     * @see Path#getFileName()
     */
    public abstract String getFileName();

    /**
     * Creates a new input stream to the resource.
     *
     * @return an input stream to the resource or null if one is not available.
     * @throws IOException if there is a problem opening the input stream
     */
    public InputStream newInputStream() throws IOException
    {
        Path path = getPath();
        if (path == null)
            return null;
        return Files.newInputStream(path, StandardOpenOption.READ);
    }

    /**
     * Readable ByteChannel for the resource.
     *
     * @return a readable {@link java.nio.channels.ByteChannel} to the resource or null if one is not available.
     * @throws IOException if unable to open the readable bytechannel for the resource.
     */
    public ReadableByteChannel newReadableByteChannel() throws IOException
    {
        Path path = getPath();
        if (path == null)
            return null;
        return Files.newByteChannel(getPath(), StandardOpenOption.READ);
    }

    /**
     * <p>List of contents of a directory {@link Resource}.</p>
     *
     * <p>Ordering is {@link java.nio.file.FileSystem} dependent, so callers may wish to sort the return value to ensure deterministic behavior.</p>
     *
     * @return a mutable list of resources contained in the directory resource,
     * or an empty immutable list if unable to build the list  (e.g. the resource is not a directory or not readable).
     * @see Resource#isDirectory()
     * @see Resource#isReadable()
     */
    public List<Resource> list()
    {
        return List.of(); // empty
    }

    /**
     * Resolve an existing Resource.
     *
     * @param subUriPath the encoded subUriPath
     * @return an existing Resource representing the requested subUriPath, or null if resource does not exist.
     * @throws IllegalArgumentException if subUriPath is invalid
     */
    public abstract Resource resolve(String subUriPath);

    /**
     * @return true if this Resource is an alias to another real Resource
     */
    public boolean isAlias()
    {
        return false;
    }

    /**
     * <p>The real URI of the resource.</p>
     * <p>If this Resource is an alias, ({@link #isAlias()}), this
     * URI will be different from {@link #getURI()}, and will point to the real name/location
     * of the Resource.</p>
     *
     * @return The real URI location of this resource.
     */
    public URI getRealURI()
    {
        return getURI();
    }

    /**
     * Copy the Resource to the new destination file or directory
     *
     * @param destination the destination file to create or directory to use.
     * @throws IOException if unable to copy the resource
     */
    public void copyTo(Path destination)
        throws IOException
    {
        Path src = getPath();
        if (src == null)
        {
            if (!isDirectory())
            {
                // use old school stream based copy
                try (InputStream in = newInputStream(); OutputStream out = Files.newOutputStream(destination))
                {
                    IO.copy(in, out);
                }
                return;
            }
            throw new UnsupportedOperationException("Directory Resources without a Path must implement copyTo");
        }

        // Do we have to copy a single file?
        if (Files.isRegularFile(src))
        {
            // Is the destination a directory?
            if (Files.isDirectory(destination))
            {
                // to a directory, preserve the filename
                Path destPath = destination.resolve(src.getFileName().toString());
                Files.copy(src, destPath, StandardCopyOption.COPY_ATTRIBUTES, StandardCopyOption.REPLACE_EXISTING);
            }
            else
            {
                // to a file, use destination as-is
                Files.copy(src, destination, StandardCopyOption.COPY_ATTRIBUTES, StandardCopyOption.REPLACE_EXISTING);
            }
            return;
        }

        // At this point this PathResource is a directory.
        assert isDirectory();

        BiFunction<Path, Path, Path> resolver = src.getFileSystem().equals(destination.getFileSystem())
            ? Path::resolve
            : Resource::resolveDifferentFileSystem;

        try (Stream<Path> entriesStream = Files.walk(src))
        {
            for (Iterator<Path> pathIterator = entriesStream.iterator(); pathIterator.hasNext();)
            {
                Path path = pathIterator.next();
                if (src.equals(path))
                    continue;

                Path relative = src.relativize(path);
                Path destPath = resolver.apply(destination, relative);

                if (LOG.isDebugEnabled())
                    LOG.debug("CopyTo: {} > {}", path, destPath);
                if (Files.isDirectory(path))
                {
                    ensureDirExists(destPath);
                }
                else
                {
                    ensureDirExists(destPath.getParent());
                    Files.copy(path, destPath, StandardCopyOption.COPY_ATTRIBUTES, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    static Path resolveDifferentFileSystem(Path path, Path relative)
    {
        for (Path segment : relative)
            path = path.resolve(segment.toString());
        return path;
    }

    void ensureDirExists(Path dir) throws IOException
    {
        if (Files.exists(dir))
        {
            if (!Files.isDirectory(dir))
            {
                throw new IOException("Conflict, unable to create directory where file exists: " + dir);
            }
            return;
        }
        Files.createDirectories(dir);
    }

    /**
     * Get a deep collection of contained resources.
     * @return A collection of all Resources deeply contained within this resource if it is a directory,
     * otherwise an empty collection is returned.
     */
    public Collection<Resource> getAllResources()
    {
        try
        {
            List<Resource> children = list();
            if (children == null || children.isEmpty())
                return List.of();

            boolean noDepth = true;

            for (Iterator<Resource> i = children.iterator(); noDepth && i.hasNext(); )
                noDepth = !i.next().isDirectory();
            if (noDepth)
                return children;

            ArrayList<Resource> deep = new ArrayList<>();
            for (Resource r: children)
            {
                deep.add(r);
                if (r.isDirectory())
                    deep.addAll(r.getAllResources());
            }
            return deep;
        }
        catch (Exception e)
        {
            throw new IllegalStateException(e);
        }
    }
}
