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
import java.nio.file.DirectoryIteratorException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.ProviderNotFoundException;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Base64;
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

    /**
     * <p>Make a Resource containing a collection of other resources</p>
     * @param resources multiple resources to combine as a single resource. Typically, they are directories.
     * @return A Resource of multiple resources.
     * @see ResourceCollection
     */
    // TODO this should be moved to ResourceFactory
    public static ResourceCollection combine(List<Resource> resources)
    {
        if (resources == null || resources.isEmpty())
            throw new IllegalArgumentException("No resources");
        return new ResourceCollection(resources);
    }

    /**
     * <p>Make a Resource containing a collection of other resources</p>
     * @param resources multiple resources to combine as a single resource. Typically, they are directories.
     * @return A Resource of multiple resources.
     * @see ResourceCollection
     */
    // TODO this should be moved to ResourceFactory
    public static ResourceCollection combine(Resource... resources)
    {
        if (resources == null || resources.length == 0)
            throw new IllegalArgumentException("No resources");
        return new ResourceCollection(List.of(resources));
    }

    public static String dump(Resource resource)
    {
        if (resource == null)
            return "null exists=false directory=false lm=-1";
        return "%s exists=%b directory=%b lm=%d"
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
     * Time resource was last modified.
     * Equivalent to {@link Files#getLastModifiedTime(Path, LinkOption...)} with the following parameter:
     * {@link #getPath()} then returning {@link FileTime#toMillis()}.
     *
     * @return the last modified time as milliseconds since unix epoch or
     * 0 if {@link Files#getLastModifiedTime(Path, LinkOption...)} throws {@link IOException}.
     */
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
     * The name of the resource.
     *
     * @return the name of the resource
     */
    public abstract String getName();

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
     * list of resource names contained in the given resource.
     * Ordering is unspecified, so callers may wish to sort the return value to ensure deterministic behavior.
     * Equivalent to {@link Files#newDirectoryStream(Path)} with parameter: {@link #getPath()} then iterating over the returned
     * {@link DirectoryStream}, taking the {@link Path#getFileName()} of each iterated entry and appending a {@code /} to
     * the file name if testing it with {@link Files#isDirectory(Path, LinkOption...)} returns true.
     *
     * @return a list of resource names contained in the given resource, or null if {@link DirectoryIteratorException} or
     * {@link IOException} was thrown while building the filename list.
     * Note: The resource names are not URL encoded.
     */
    public List<String> list() // TODO: should return Path's
    {
        if (!isDirectory())
            return null;
        try (DirectoryStream<Path> dir = Files.newDirectoryStream(getPath()))
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
            return entries;
        }
        catch (DirectoryIteratorException e)
        {
            LOG.debug("Directory list failure", e);
        }
        catch (IOException e)
        {
            LOG.debug("Directory list access failure", e);
        }
        return null;
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
        return getAlias() != null;
    }

    /**
     * @return The canonical Alias of this resource or null if none.
     */
    public URI getAlias()
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

    /**
     * Generate a weak ETag reference for this Resource.
     *
     * @return the weak ETag reference for this resource.
     */
    public String getWeakETag()
    {
        return getWeakETag("");
    }

    public String getWeakETag(String suffix)
    {
        StringBuilder b = new StringBuilder(32);
        b.append("W/\"");

        String name = getName();
        int length = name.length();
        long lhash = 0;
        for (int i = 0; i < length; i++)
        {
            lhash = 31 * lhash + name.charAt(i);
        }

        Base64.Encoder encoder = Base64.getEncoder().withoutPadding();
        b.append(encoder.encodeToString(longToBytes(lastModified() ^ lhash)));
        b.append(encoder.encodeToString(longToBytes(length() ^ lhash)));
        b.append(suffix);
        b.append('"');
        return b.toString();
    }

    private static byte[] longToBytes(long value)
    {
        byte[] result = new byte[Long.BYTES];
        for (int i = Long.BYTES - 1; i >= 0; i--)
        {
            result[i] = (byte)(value & 0xFF);
            value >>= 8;
        }
        return result;
    }

    public Collection<Resource> getAllResources()
    {
        try
        {
            ArrayList<Resource> deep = new ArrayList<>();
            {
                List<String> list = list();
                if (list != null)
                {
                    for (String i : list)
                    {
                        Resource r = resolve(i);
                        if (r.isDirectory())
                            deep.addAll(r.getAllResources());
                        else
                            deep.add(r);
                    }
                }
            }
            return deep;
        }
        catch (Exception e)
        {
            throw new IllegalStateException(e);
        }
    }
}
