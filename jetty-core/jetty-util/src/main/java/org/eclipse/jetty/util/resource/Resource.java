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
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileAttribute;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;

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
        String thisURIString = URIUtil.correctURI(thisURI).toASCIIString();
        String otherURIString = URIUtil.correctURI(otherURI).toASCIIString();

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
     * Copy the Resource to the new destination file or directory.
     *
     * <p>If this Resource is a File:</p>
     * <ul>
     *     <li>And the {@code destination} does not exist then {@link IO#copyFile(Path, Path)} is used.</li>
     *     <li>And the {@code destination} is a File then {@link IO#copyFile(Path, Path)} is used.</li>
     *     <li>And the {@code destination} is a Directory then
     *         a new {@link Path} reference is created in the destination with the same
     *         filename as this Resource, which is used via {@link IO#copyFile(Path, Path)}.</li>
     * </ul>
     *
     * <p>If this Resource is a Directory:</p>
     * <ul>
     *     <li>And the {@code destination} does not exist then
     *         the destination is created as a directory via {@link Files#createDirectories(Path, FileAttribute[])}
     *         before the {@link IO#copyDir(Path, Path)} method is used.</li>
     *     <li>And the {@code destination} is a File then this results in an {@link IllegalArgumentException}.</li>
     *     <li>And the {@code destination} is a Directory then all files in this Resource
     *         directory tree are copied to the {@code destination}, using {@link IO#copyFile(Path, Path)}
     *         maintaining the same directory structure.</li>
     * </ul>
     *
     * <p>If this Resource is not backed by a {@link Path}, use {@link #newInputStream()}:</p>
     * <ul>
     *     <li>And the {@code destination} does not exist, copy {@link InputStream}
     *         to the {@code destination} as a new file.</li>
     *     <li>And the {@code destination} is a File, copy {@link InputStream}
     *         to the existing {@code destination} file.</li>
     *     <li>And the {@code destination} is a Directory, copy {@link InputStream}
     *         to a new {@code destination} file in the destination directory
     *         based on this the result of {@link #getFileName()} as the filename.</li>
     * </ul>
     *
     * @param destination the destination file or directory to use (created if it does not exist).
     * @throws IOException if unable to copy the resource
     */
    public void copyTo(Path destination)
        throws IOException
    {
        Path src = getPath();
        if (src == null)
        {
            // this implementation is not backed by a Path.

            // is this a Directory?
            if (isDirectory())
            {
                // if we reached this point, we have a Resource implementation that needs custom copyTo.
                throw new UnsupportedOperationException("Directory Resources without a Path must implement copyTo: " + this);
            }

            // assume that this Resource is a File.
            String filename = getFileName();
            if (StringUtil.isBlank(filename))
            {
                throw new UnsupportedOperationException("File Resources without a Path must implement getFileName: " + this);
            }

            Path destFile = destination;
            if (Files.isDirectory(destFile))
            {
                destFile = destFile.resolve(filename);
            }

            // use old school stream based copy (without a Path)
            try (InputStream in = newInputStream(); // use non-path newInputStream
                 OutputStream out = Files.newOutputStream(destFile,
                     StandardOpenOption.CREATE,
                     StandardOpenOption.WRITE,
                     StandardOpenOption.TRUNCATE_EXISTING))
            {
                IO.copy(in, out);
            }
            return;
        }

        // Is this a File resource?
        if (Files.isRegularFile(src))
        {
            if (Files.isDirectory(destination))
            {
                // to a directory, preserve the filename
                Path destPath = destination.resolve(src.getFileName().toString());
                IO.copyFile(src, destPath);
            }
            else
            {
                // to a file, use destination as-is
                IO.copyFile(src, destination);
            }
            return;
        }

        // At this point this PathResource is a directory,
        // wanting to copy to a destination directory (that might not exist yet)
        assert isDirectory();

        IO.copyDir(src, destination);
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
            {
                Resource resource = i.next();
                if (resource.isDirectory())
                {
                    // If the directory is a symlink we do not want to go any deeper.
                    Path resourcePath = resource.getPath();
                    if (resourcePath == null || !Files.isSymbolicLink(resourcePath))
                        noDepth = false;
                }
            }
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

    public boolean isSameFile(Path path)
    {
        Path resourcePath = getPath();
        if (Objects.equals(path, resourcePath))
            return true;
        try
        {
            if (Files.isSameFile(path, resourcePath))
                return true;
        }
        catch (Throwable t)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("ignored", t);
        }
        return false;
    }
}
