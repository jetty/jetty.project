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

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.channels.ReadableByteChannel;
import java.nio.file.CopyOption;
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
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

import org.eclipse.jetty.util.FileID;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.Index;
import org.eclipse.jetty.util.URIUtil;
import org.eclipse.jetty.util.UrlEncoded;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * Abstract resource class.
 * <p>
 * This class provides a resource abstraction, where a resource may be
 * a file, a URL or an entry in a jar file.
 * </p>
 */
public abstract class Resource
{
    private static final Logger LOG = LoggerFactory.getLogger(Resource.class);
    private static final LinkOption[] NO_FOLLOW_LINKS = new LinkOption[]{LinkOption.NOFOLLOW_LINKS};
    private static final LinkOption[] FOLLOW_LINKS = new LinkOption[]{};

    private static final Index<String> ALLOWED_SCHEMES = new Index.Builder<String>()
        .caseSensitive(false)
        .with("file:")
        .with("jrt:")
        .with("jar:")
        .build();

    /**
     * <p>Mount a URI if it is needed.</p>
     *
     * @param uri The URI to mount that may require a FileSystem (e.g. "jar:file://tmp/some.jar!/directory/file.txt")
     * @return A reference counted {@link Mount} for that file system or null. Callers should call {@link Mount#close()} once
     * they no longer require any resources from a mounted resource.
     * @throws IllegalArgumentException If the uri could not be mounted.
     */
    static Resource.Mount mountIfNeeded(URI uri)
    {
        if (uri == null)
            return null;
        String scheme = uri.getScheme();
        if (scheme == null)
            return null;
        if (!FileID.isArchive(uri))
            return null;
        try
        {
            if (scheme.equalsIgnoreCase("jar"))
            {
                return FileSystemPool.INSTANCE.mount(uri);
            }
            // TODO: review contract, should this be null, or an empty mount?
            return null;
        }
        catch (IOException ioe)
        {
            throw new IllegalArgumentException(ioe);
        }
    }

    /**
     * <p>Make a Resource containing a collection of other resources</p>
     * @param resources multiple resources to combine as a single resource. Typically, they are directories.
     * @return A Resource of multiple resources.
     * @see ResourceCollection
     */
    public static ResourceCollection of(List<Resource> resources)
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
    public static ResourceCollection of(Resource... resources)
    {
        if (resources == null || resources.length == 0)
            throw new IllegalArgumentException("No resources");

        return new ResourceCollection(List.of(resources));
    }

    /**
     * <p>Convert a String into a URI suitable for use as a Resource.</p>
     *
     * @param resource If the string starts with one of the ALLOWED_SCHEMES, then it is assumed to be a
     * representation of a {@link URI}, otherwise it is treated as a {@link Path}.
     * @return The {@link URI} form of the resource.
     */
    // TODO move to URIUtil
    public static URI toURI(String resource)
    {
        Objects.requireNonNull(resource);

        // Only try URI for string for known schemes, otherwise assume it is a Path
        URI uri = (ALLOWED_SCHEMES.getBest(resource) != null)
            ? URI.create(resource)
            : Paths.get(resource).toUri();

        return uri;
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
    static Resource createResource(URI uri)
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
     * Construct a system resource from a string.
     * The resource is tried as classloader resource before being
     * treated as a normal resource.
     *
     * @param resource Resource as string representation
     * @return The new Resource
     * TODO move to ResourceFactory
     */
    public static Resource newSystemResource(String resource)
    {
        return newSystemResource(resource, null);
    }

    /**
     * Construct a system resource from a string.
     * The resource is tried as classloader resource before being
     * treated as a normal resource.
     *
     * @param resource Resource as string representation
     * @param mountConsumer a consumer that receives the mount in case the resource needs mounting
     * @return The new Resource
     * TODO move to ResourceFactory
     */
    public static Resource newSystemResource(String resource, Consumer<Mount> mountConsumer)
    {
        URL url = null;
        // Try to format as a URL?
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        if (loader != null)
        {
            try
            {
                url = loader.getResource(resource);
                if (url == null && resource.startsWith("/"))
                    url = loader.getResource(resource.substring(1));
            }
            catch (IllegalArgumentException e)
            {
                LOG.trace("IGNORED", e);
                // Catches scenario where a bad Windows path like "C:\dev" is
                // improperly escaped, which various downstream classloaders
                // tend to have a problem with
                url = null;
            }
        }
        if (url == null)
        {
            loader = Resource.class.getClassLoader();
            if (loader != null)
            {
                url = loader.getResource(resource);
                if (url == null && resource.startsWith("/"))
                    url = loader.getResource(resource.substring(1));
            }
        }

        if (url == null)
        {
            url = ClassLoader.getSystemResource(resource);
            if (url == null && resource.startsWith("/"))
                url = ClassLoader.getSystemResource(resource.substring(1));
        }

        if (url == null)
            return null;

        try
        {
            URI uri = url.toURI();
            if (mountConsumer != null && uri.getScheme().equalsIgnoreCase("jar"))
            {
                Mount mount = mountIfNeeded(uri);
                if (mount != null)
                {
                    mountConsumer.accept(mount);
                    return mount.root();
                }
            }
            return createResource(uri);
        }
        catch (URISyntaxException e)
        {
            throw new IllegalArgumentException("Error creating resource from URL: " + url, e);
        }
    }

    /**
     * Return true if the Resource r is contained in the Resource containingResource, either because
     * containingResource is a folder or a jar file or any form of resource capable of containing other resources.
     *
     * @param r the contained resource
     * @param containingResource the containing resource
     * @return true if the Resource is contained, false otherwise
     */
    public static boolean isContainedIn(Resource r, Resource containingResource)
    {
        return r.isContainedIn(containingResource);
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
     * Return true if the passed Resource represents the same resource as the Resource.
     * For many resource types, this is equivalent to {@link #equals(Object)}, however
     * for resources types that support aliasing, this maybe some other check (e.g. {@link java.nio.file.Files#isSameFile(Path, Path)}).
     *
     * @param resource The resource to check
     * @return true if the passed resource represents the same resource.
     */
    public boolean isSame(Resource resource)
    {
        return equals(resource);
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
     * Checks if the resource supports being loaded as a memory-mapped ByteBuffer.
     *
     * @return true if the resource supports memory-mapped ByteBuffer, false otherwise.
     */
    public boolean isMemoryMappable()
    {
        return false;
    }

    /**
     * Deletes the given resource
     * Equivalent to {@link Files#deleteIfExists(Path)} with the following parameter:
     * {@link #getPath()}.
     *
     * @return true if the resource was deleted by this method; false if the file could not be deleted because it did not exist
     * or if {@link Files#deleteIfExists(Path)} throws {@link IOException}.
     */
    public boolean delete()
    {
        try
        {
            return Files.deleteIfExists(getPath());
        }
        catch (IOException e)
        {
            LOG.trace("IGNORED", e);
            return false;
        }
    }

    /**
     * Rename the given resource
     * Equivalent to {@link Files#move(Path, Path, CopyOption...)} with the following parameter:
     * {@link #getPath()}, {@code dest.getPath()} then returning the result of {@link Files#exists(Path, LinkOption...)}
     * on the {@code Path} returned by {@code move()}.
     *
     * @param dest the destination name for the resource
     * @return true if the resource was renamed, false if the resource didn't exist or was unable to be renamed.
     */
    public boolean renameTo(Resource dest)
    {
        try
        {
            Path result = Files.move(getPath(), dest.getPath());
            return Files.exists(result, NO_FOLLOW_LINKS);
        }
        catch (IOException e)
        {
            LOG.trace("IGNORED", e);
            return false;
        }
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
     * {@inheritDoc}
     */
    public Resource resolve(String subUriPath)
    {
        // Check that the path is within the root,
        // but use the original path to create the
        // resource, to preserve aliasing.
        // TODO should we canonicalize here? Or perhaps just do a URI safe encoding
        if (URIUtil.normalizePath(subUriPath) == null)
            throw new IllegalArgumentException(subUriPath);

        if (URIUtil.SLASH.equals(subUriPath))
            return this;

        // Sub-paths are always resolved under the given URI,
        // we compensate for input sub-paths like "/subdir"
        // where default resolve behavior would be to treat
        // that like an absolute path.
        while (subUriPath.startsWith(URIUtil.SLASH))
        {
            subUriPath = subUriPath.substring(1);
        }

        URI uri = getURI();
        URI resolvedUri;
        if (uri.isOpaque())
        {
            // TODO create a subclass with an optimized implementation of this.
            // The 'jar:file:/some/path/my.jar!/foo/bar' URI is opaque b/c the jar: scheme is not followed by //
            // so we take the scheme-specific part (i.e.: file:/some/path/my.jar!/foo/bar) and interpret it as a URI,
            // use it to resolve the subPath then re-prepend the jar: scheme before re-creating the URI.
            String scheme = uri.getScheme();
            URI subUri = URI.create(uri.getSchemeSpecificPart());
            if (subUri.isOpaque())
                throw new IllegalArgumentException("Unsupported doubly opaque URI: " + uri);

            if (!subUri.getPath().endsWith(URIUtil.SLASH))
                subUri = URI.create(subUri + URIUtil.SLASH);

            URI subUriResolved = subUri.resolve(subUriPath);
            resolvedUri = URI.create(scheme + ":" + subUriResolved);
        }
        else
        {
            if (!uri.getPath().endsWith(URIUtil.SLASH))
                uri = URI.create(uri + URIUtil.SLASH);
            resolvedUri = uri.resolve(subUriPath);
        }
        return createResource(resolvedUri);
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
     * Get the raw (decoded if possible) Filename for this Resource.
     * This is the last segment of the path.
     *
     * @return the raw / decoded filename for this resource
     */
    private String getFileName()
    {
        try
        {
            // if a Resource supports File
            Path path = getPath();
            if (path != null)
            {
                return path.getFileName().toString();
            }
        }
        catch (Throwable ignored)
        {
        }

        // All others use raw getName
        try
        {
            String rawName = getName(); // gets long name "/foo/bar/xxx"
            int idx = rawName.lastIndexOf('/');
            if (idx == rawName.length() - 1)
            {
                // hit a tail slash, aka a name for a directory "/foo/bar/"
                idx = rawName.lastIndexOf('/', idx - 1);
            }

            String encodedFileName;
            if (idx >= 0)
            {
                encodedFileName = rawName.substring(idx + 1);
            }
            else
            {
                encodedFileName = rawName; // entire name
            }
            return UrlEncoded.decodeString(encodedFileName, 0, encodedFileName.length(), UTF_8);
        }
        catch (Throwable ignored)
        {
        }

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

    /**
     * Certain {@link Resource}s (e.g.: JAR files) require mounting before they can be used. This class is the representation
     * of such mount allowing the use of more {@link Resource}s.
     * Mounts are {@link Closeable} because they always contain resources (like file descriptors) that must eventually
     * be released.
     */
    public interface Mount extends Closeable
    {
        /**
         * Return the root {@link Resource} made available by this mount.
         *
         * @return the resource.
         */
        Resource root();
    }
}
