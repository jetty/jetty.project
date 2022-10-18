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
import java.net.URISyntaxException;
import java.nio.channels.ReadableByteChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.ProviderNotFoundException;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;

import org.eclipse.jetty.util.IO;
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
    private static final LinkOption[] FOLLOW_LINKS = new LinkOption[]{};

    public static String dump(Resource resource)
    {
        if (resource == null)
            return "null exists=false directory=false lm=-1";
        return "%s exists=%b directory=%b lm=%s"
            .formatted(resource.toString(), resource.exists(), resource.isDirectory(), resource.lastModified());
    }

    /**
     * Construct a resource from a uri.
     *
     * @param uri A URI.
     * @return A Resource object.
     */
    static Resource create(URI uri)
    {
        try
        {
            // If the URI is not absolute
            if (!uri.isAbsolute())
            {
                // If it is an absolute path,
                if (uri.toString().startsWith("/"))
                    // just add the scheme
                    uri = new URI("file", uri.toString(), null);
                else
                    // otherwise resolve against the current directory
                    uri = Paths.get("").toAbsolutePath().toUri().resolve(uri);

                // Correct any `file:/path` to `file:///path` mistakes
                uri = URIUtil.correctFileURI(uri);
            }

            // If the scheme is allowed by PathResource, we can build a non-mounted PathResource.
            if (PathResource.ALLOWED_SCHEMES.contains(uri.getScheme()))
                return new PathResource(uri);

            return new MountedPathResource(uri);
        }
        catch (URISyntaxException | ProviderNotFoundException | IOException ex)
        {
            throw new IllegalArgumentException(ex);
        }
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
     * Return an Iterator of all Resource's referenced in this Resource.
     *
     * <p>
     *     This is meaningful if you have a Composite Resource, otherwise it will be a single entry Iterator.
     * </p>
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
     * Equivalent to {@link Files#isDirectory(Path, LinkOption...)} with the following parameter:
     * {@link #getPath()}.
     *
     * @return true if the represented resource is a container/directory.
     */
    public boolean isDirectory()
    {
        return Files.isDirectory(getPath(), FOLLOW_LINKS);
    }

    /**
     * The time the resource was last modified.
     *
     * Equivalent to {@link Files#getLastModifiedTime(Path, LinkOption...)} with the following parameter:
     * {@link #getPath()} then returning {@link FileTime#toInstant()}.
     *
     * @return the last modified time instant, or {@link Instant#EPOCH} if unable to obtain last modified.
     */
    public Instant lastModified()
    {
        Path path = getPath();
        if (path == null)
            return Instant.EPOCH;

        if (!Files.exists(path))
            return Instant.EPOCH;

        try
        {
            FileTime ft = Files.getLastModifiedTime(path, FOLLOW_LINKS);
            return ft.toInstant();
        }
        catch (IOException e)
        {
            LOG.trace("IGNORED", e);
            return Instant.EPOCH;
        }
    }

    /**
     * Length of the resource.
     * Equivalent to {@link Files#size(Path)} with the following parameter:
     * {@link #getPath()}.
     *
     * @return the length of the resource or 0 if {@link Files#size(Path)} throws {@link IOException}.
     */
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

    /**
     * URI representing the resource.
     *
     * @return an URI representing the given resource
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
     * @return an input stream to the resource
     * @throws IOException if unable to open the input stream
     */
    public InputStream newInputStream() throws IOException
    {
        return Files.newInputStream(getPath(), StandardOpenOption.READ);
    }

    /**
     * Readable ByteChannel for the resource.
     *
     * @return an readable bytechannel to the resource or null if one is not available.
     * @throws IOException if unable to open the readable bytechannel for the resource.
     */
    public ReadableByteChannel newReadableByteChannel() throws IOException
    {
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
     * Resolve a new Resource from an encoded subUriPath.
     *
     * @param subUriPath the encoded subUriPath
     * @return a Resource representing the subUriPath
     * @throws IllegalArgumentException if subUriPath is invalid
     */
    public Resource resolve(String subUriPath)
    {
        // Check that the path is within the root,
        // but use the original path to create the
        // resource, to preserve aliasing.
        // TODO do a URI safe encoding?
        if (URIUtil.isNotNormalWithinSelf(subUriPath))
            throw new IllegalArgumentException(subUriPath);

        if (URIUtil.SLASH.equals(subUriPath))
            return this;

        // Sub-paths are always resolved under the given URI,
        // we compensate for input sub-paths like "/subdir"
        // where default resolve behavior would be to treat
        // that like an absolute path.
        while (subUriPath.startsWith(URIUtil.SLASH))
        {
            // TODO XXX this appears entirely unnecessary and inefficient.  We already have utilities
            //      to handle appending path strings with/without slashes.
            subUriPath = subUriPath.substring(1);
        }

        URI uri = getURI();
        URI resolvedUri = URIUtil.addPath(uri, subUriPath);
        return create(resolvedUri);
    }

    /**
     * @return true if this Resource is an alias to another real Resource
     */
    public boolean isAlias()
    {
        return getTargetURI() != null;
    }

    /**
     * If this Resource is an alias pointing to a different location,
     * return the target location as URI.
     *
     * @return The target URI location of this resource,
     *      or null if there is no target URI location (eg: not an alias, or a symlink)
     */
    public URI getTargetURI()
    {
        return null;
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
