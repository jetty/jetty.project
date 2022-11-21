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

import org.eclipse.jetty.util.IO;

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
     * @return the path.
     */
    public abstract Path getPath();

    /**
     * Return true if this resource is contained in the Resource r, either because
     * r is a folder or a jar file or any form of resource capable of containing other resources.
     *
     * @param r the containing resource
     * @return true if this Resource is contained, false otherwise
     */
    public abstract boolean isContainedIn(Resource r);

    /**
     * <p>Return an Iterator of all Resource's referenced in this Resource.</p>
     * <p>This is meaningful if you have a Composite Resource, otherwise it will be a single entry Iterator of this resource.</p>
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
     * @return a URI representing the given resource
     */
    public abstract URI getURI();

    /**
     * The full name of the resource.
     *
     * @return the full name of the resource, or null if not backed by a Path
     */
    public abstract String getName();

    /**
     * <p>The file name of the resource.</p>
     *
     * <p>This is the last segment of the path.</p>
     *
     * @return the filename of the resource, or "" if there are no path segments (eg: path of "/"), or null if not backed by a Path
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
     * <p>List of existing Resources contained in the given resource.</p>
     *
     * <p>Ordering is unspecified, so callers may wish to sort the return value to ensure deterministic behavior.</p>
     *
     * @return a mutable list of resources contained in the tracked resource,
     * or an empty immutable list if unable to build the list.
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
     * Copy the Resource to the new destination file.
     * <p>
     * Will not replace existing destination file.
     *
     * @param destination the destination file to create
     * @throws IOException if unable to copy the resource
     */
    public void copyTo(Path destination)
        throws IOException
    {
        if (Files.exists(destination))
            throw new IllegalArgumentException(destination + " exists");

        // attempt simple file copy
        Path src = getPath();
        if (src != null)
        {
            // TODO ATOMIC_MOVE seems useless for a copy and REPLACE_EXISTING contradicts the
            //  javadoc that explicitly states "Will not replace existing destination file."
            Files.copy(src, destination,
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.COPY_ATTRIBUTES, StandardCopyOption.REPLACE_EXISTING);
            return;
        }

        // use old school stream based copy
        try (InputStream in = newInputStream();
             OutputStream out = Files.newOutputStream(destination))
        {
            IO.copy(in, out);
        }
    }

    public Collection<Resource> getAllResources()
    {
        try
        {
            ArrayList<Resource> deep = new ArrayList<>();
            for (Resource r: list())
            {
                if (r.isDirectory())
                    deep.addAll(r.getAllResources());
                else
                    deep.add(r);
            }
            return deep;
        }
        catch (Exception e)
        {
            throw new IllegalStateException(e);
        }
    }
}
