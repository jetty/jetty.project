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
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.channels.ReadableByteChannel;
import java.nio.file.CopyOption;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.ProviderNotFoundException;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.StringTokenizer;
import java.util.function.Consumer;

import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.Loader;
import org.eclipse.jetty.util.MultiMap;
import org.eclipse.jetty.util.StringUtil;
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
public abstract class Resource implements ResourceFactory
{
    private static final Logger LOG = LoggerFactory.getLogger(Resource.class);
    private static final LinkOption[] NO_FOLLOW_LINKS = new LinkOption[]{LinkOption.NOFOLLOW_LINKS};
    private static final LinkOption[] FOLLOW_LINKS = new LinkOption[]{};

    public static boolean __defaultUseCaches = true;

    /**
     * Change the default setting for url connection caches.
     * Subsequent URLConnections will use this default.
     *
     * @param useCaches true to enable URL connection caches, false otherwise.
     */
    public static void setDefaultUseCaches(boolean useCaches)
    {
        __defaultUseCaches = useCaches;
    }

    public static boolean getDefaultUseCaches()
    {
        return __defaultUseCaches;
    }

    public static Resource.Mount newJarResource(String resource) throws IOException
    {
        URI uri = URI.create(resource);
        // If the URI has no scheme, we consider the string actually was a path.
        if (uri.getScheme() == null)
            uri = Paths.get(resource).toUri();
        return newJarResource(uri);
    }

    public static Resource.Mount newJarResource(URI uri) throws IOException
    {
        if (!uri.getScheme().equalsIgnoreCase("jar"))
            throw new IllegalArgumentException("not an allowed URI: " + uri);
        return FileSystemPool.INSTANCE.mount(uri);
    }

    public static Resource.Mount newJarResource(Path path) throws IOException
    {
        URI pathUri = path.toUri();
        if (!pathUri.getScheme().equalsIgnoreCase("file"))
            throw new IllegalArgumentException("not an allowed path: " + path);
        URI jarUri = URI.create("jar:" + pathUri + "!/");
        return FileSystemPool.INSTANCE.mount(jarUri);
    }

    /**
     * Construct a resource from a url.
     *
     * @param url A URL.
     * @return A Resource object.
     */
    public static Resource newResource(URL url)
    {
        try
        {
            return newResource(url.toURI());
        }
        catch (IOException | URISyntaxException e)
        {
            throw new IllegalArgumentException("Error creating resource from URL: " + url, e);
        }
    }

    /**
     * Construct a resource from a string.
     *
     * @param resource A URL or filename.
     * @return A Resource object.
     * @throws MalformedURLException Problem accessing URI
     */
    public static Resource newResource(String resource) throws IOException
    {
        URI uri;
        try
        {
            uri = URI.create(resource);

            // If the URI has no scheme we consider the string actually is a path,
            // if the scheme is 1 character long, we consider it's a Windows drive letter.
            if (uri.getScheme() == null || uri.getScheme().length() == 1)
                uri = Paths.get(resource).toUri();
        }
        catch (IllegalArgumentException iae)
        {
            // If the URI cannot be parsed we consider the string actually is a path.
            uri = Paths.get(resource).toUri();
        }
        return newResource(uri);
    }

    /**
     * Construct a resource from a uri.
     *
     * @param uri A URI.
     * @return A Resource object.
     * @throws MalformedURLException Problem accessing URI
     */
    public static Resource newResource(URI uri) throws IOException
    {
        if (!uri.isAbsolute())
            throw new IllegalArgumentException("not an absolute uri: " + uri);

        // If the scheme is allowed by PathResource, we can build a non-mounted PathResource.
        if (PathResource.ALLOWED_SCHEMES.contains(uri.getScheme()))
            return new PathResource(uri);

        // Otherwise build a MountedPathResource.
        try
        {
            return new MountedPathResource(uri);
        }
        catch (NoSuchFileException nsfe)
        {
            // TODO is that still a valid codepath? can't we get rid of BadResource?
            // The filesystem cannot be created for that URI (e.g.: non-existent jar file).
            return new BadResource(uri, nsfe.toString());
        }
        catch (ProviderNotFoundException ex)
        {
            throw new IllegalArgumentException(ex);
        }
    }

    /**
     * Construct a Resource from provided path
     *
     * @param path the path
     * @return the Resource for the provided path
     */
    public static Resource newResource(Path path)
    {
        try
        {
            return newResource(path.toUri());
        }
        catch (IOException e)
        {
            throw new IllegalArgumentException("Unsupported path: " + path, e);
        }
    }

    /**
     * Construct a system resource from a string.
     * The resource is tried as classloader resource before being
     * treated as a normal resource.
     *
     * @param resource Resource as string representation
     * @return The new Resource
     * @throws IOException Problem accessing resource.
     */
    public static Resource newSystemResource(String resource) throws IOException
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
     * @throws IOException Problem accessing resource.
     */
    public static Resource newSystemResource(String resource, Consumer<Mount> mountConsumer) throws IOException
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
                Mount mount = newJarResource(uri);
                mountConsumer.accept(mount);
                return mount.root();
            }
            return newResource(uri);
        }
        catch (IOException | URISyntaxException e)
        {
            throw new IllegalArgumentException("Error creating resource from URL: " + url, e);
        }
    }

    /**
     * Find a classpath resource.
     * The {@link java.lang.Class#getResource(String)} method is used to lookup the resource. If it is not
     * found, then the {@link Loader#getResource(String)} method is used.
     * If it is still not found, then {@link ClassLoader#getSystemResource(String)} is used.
     * Unlike {@link ClassLoader#getSystemResource(String)} this method does not check for normal resources.
     *
     * @param resource the relative name of the resource
     * @return Resource or null
     */
    public static Resource newClassPathResource(String resource)
    {
        URL url = Resource.class.getResource(resource);

        if (url == null)
            url = Loader.getResource(resource);
        if (url == null)
            return null;
        return newResource(url);
    }

    public static boolean isContainedIn(Resource r, Resource containingResource) throws MalformedURLException
    {
        return r.isContainedIn(containingResource);
    }

    public abstract Path getPath();

    public abstract boolean isContainedIn(Resource r) throws MalformedURLException;

    /**
     * Return true if the passed Resource represents the same resource as the Resource.
     * For many resource types, this is equivalent to {@link #equals(Object)}, however
     * for resources types that support aliasing, this maybe some other check (e.g. {@link java.nio.file.Files#isSameFile(Path, Path)}).
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
     * @return true if the represented resource exists.
     */
    public boolean exists()
    {
        return Files.exists(getPath(), NO_FOLLOW_LINKS);
    }

    /**
     * Equivalent to {@link Files#isDirectory(Path, LinkOption...)} with the following parameter:
     * {@link #getPath()}.
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
     * Input stream to the resource
     *
     * @return an input stream to the resource
     * @throws IOException if unable to open the input stream
     * @deprecated Replace with {@link #getPath()} and {@link Files#newInputStream(Path, OpenOption...)}.
     */
    @Deprecated(forRemoval = true)
    public InputStream getInputStream() throws IOException
    {
        return Files.newInputStream(getPath(), StandardOpenOption.READ);
    }

    /**
     * Readable ByteChannel for the resource.
     *
     * @return an readable bytechannel to the resource or null if one is not available.
     * @throws IOException if unable to open the readable bytechannel for the resource.
     * @deprecated Replace with {@link #getPath()} and {@link Files#newByteChannel(Path, OpenOption...)}.
     */
    @Deprecated(forRemoval = true)
    public ReadableByteChannel getReadableByteChannel() throws IOException
    {
        return Files.newByteChannel(getPath(), StandardOpenOption.READ);
    }

    /**
     * Deletes the given resource
     * Equivalent to {@link Files#deleteIfExists(Path)} with the following parameter:
     * {@link #getPath()}.
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
    public List<String> list()
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

    public Resource resolve(String subUriPath) throws IOException
    {
        // Check that the path is within the root,
        // but use the original path to create the
        // resource, to preserve aliasing.
        if (URIUtil.canonicalPath(subUriPath) == null)
            throw new MalformedURLException(subUriPath);

        if (URIUtil.SLASH.equals(subUriPath))
            return this;

        // Sub-paths are always resolved under the given URI,
        // we compensate for input sub-paths like "/subdir"
        // where default resolve behavior would be to treat
        // that like an absolute path.
        while (subUriPath.startsWith(URIUtil.SLASH))
            subUriPath = subUriPath.substring(1);

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
        return newResource(resolvedUri);
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
     * Get the resource list as a HTML directory listing.
     *
     * @param base The base URL
     * @param parent True if the parent directory should be included
     * @param query query params
     * @return String of HTML
     * @throws IOException on failure to generate a list.
     */
    public String getListHTML(String base, boolean parent, String query) throws IOException
    {
        // This method doesn't check aliases, so it is OK to canonicalize here.
        base = URIUtil.canonicalPath(base);
        if (base == null || !isDirectory())
            return null;

        List<String> rawListing = list();
        if (rawListing == null)
        {
            return null;
        }

        boolean sortOrderAscending = true;
        String sortColumn = "N"; // name (or "M" for Last Modified, or "S" for Size)

        // check for query
        if (query != null)
        {
            MultiMap<String> params = new MultiMap<>();
            UrlEncoded.decodeUtf8To(query, 0, query.length(), params);

            String paramO = params.getString("O");
            String paramC = params.getString("C");
            if (StringUtil.isNotBlank(paramO))
            {
                if (paramO.equals("A"))
                {
                    sortOrderAscending = true;
                }
                else if (paramO.equals("D"))
                {
                    sortOrderAscending = false;
                }
            }
            if (StringUtil.isNotBlank(paramC))
            {
                if (paramC.equals("N") || paramC.equals("M") || paramC.equals("S"))
                {
                    sortColumn = paramC;
                }
            }
        }

        // Gather up entries
        List<Resource> items = new ArrayList<>();
        for (String l : rawListing)
        {
            Resource item = resolve(l);
            items.add(item);
        }

        // Perform sort
        if (sortColumn.equals("M"))
        {
            items.sort(ResourceCollators.byLastModified(sortOrderAscending));
        }
        else if (sortColumn.equals("S"))
        {
            items.sort(ResourceCollators.bySize(sortOrderAscending));
        }
        else
        {
            items.sort(ResourceCollators.byName(sortOrderAscending));
        }

        String decodedBase = URIUtil.decodePath(base);
        String title = "Directory: " + deTag(decodedBase);

        StringBuilder buf = new StringBuilder(4096);

        // Doctype Declaration (HTML5)
        buf.append("<!DOCTYPE html>\n");
        buf.append("<html lang=\"en\">\n");

        // HTML Header
        buf.append("<head>\n");
        buf.append("<meta charset=\"utf-8\">\n");
        buf.append("<link href=\"jetty-dir.css\" rel=\"stylesheet\" />\n");
        buf.append("<title>");
        buf.append(title);
        buf.append("</title>\n");
        buf.append("</head>\n");

        // HTML Body
        buf.append("<body>\n");
        buf.append("<h1 class=\"title\">").append(title).append("</h1>\n");

        // HTML Table
        final String ARROW_DOWN = "&nbsp; &#8681;";
        final String ARROW_UP = "&nbsp; &#8679;";

        buf.append("<table class=\"listing\">\n");
        buf.append("<thead>\n");

        String arrow = "";
        String order = "A";
        if (sortColumn.equals("N"))
        {
            if (sortOrderAscending)
            {
                order = "D";
                arrow = ARROW_UP;
            }
            else
            {
                order = "A";
                arrow = ARROW_DOWN;
            }
        }

        buf.append("<tr><th class=\"name\"><a href=\"?C=N&O=").append(order).append("\">");
        buf.append("Name").append(arrow);
        buf.append("</a></th>");

        arrow = "";
        order = "A";
        if (sortColumn.equals("M"))
        {
            if (sortOrderAscending)
            {
                order = "D";
                arrow = ARROW_UP;
            }
            else
            {
                order = "A";
                arrow = ARROW_DOWN;
            }
        }

        buf.append("<th class=\"lastmodified\"><a href=\"?C=M&O=").append(order).append("\">");
        buf.append("Last Modified").append(arrow);
        buf.append("</a></th>");

        arrow = "";
        order = "A";
        if (sortColumn.equals("S"))
        {
            if (sortOrderAscending)
            {
                order = "D";
                arrow = ARROW_UP;
            }
            else
            {
                order = "A";
                arrow = ARROW_DOWN;
            }
        }
        buf.append("<th class=\"size\"><a href=\"?C=S&O=").append(order).append("\">");
        buf.append("Size").append(arrow);
        buf.append("</a></th></tr>\n");
        buf.append("</thead>\n");

        buf.append("<tbody>\n");

        String encodedBase = hrefEncodeURI(base);

        if (parent)
        {
            // Name
            buf.append("<tr><td class=\"name\"><a href=\"");
            buf.append(URIUtil.addPaths(encodedBase, "../"));
            buf.append("\">Parent Directory</a></td>");
            // Last Modified
            buf.append("<td class=\"lastmodified\">-</td>");
            // Size
            buf.append("<td>-</td>");
            buf.append("</tr>\n");
        }

        DateFormat dfmt = DateFormat.getDateTimeInstance(DateFormat.MEDIUM,
            DateFormat.MEDIUM);
        for (Resource item : items)
        {
            String name = item.getFileName();
            if (StringUtil.isBlank(name))
            {
                continue; // skip
            }

            if (item.isDirectory() && !name.endsWith("/"))
            {
                name += URIUtil.SLASH;
            }

            // Name
            buf.append("<tr><td class=\"name\"><a href=\"");
            String path = URIUtil.addEncodedPaths(encodedBase, URIUtil.encodePath(name));
            buf.append(path);
            buf.append("\">");
            buf.append(deTag(name));
            buf.append("&nbsp;");
            buf.append("</a></td>");

            // Last Modified
            buf.append("<td class=\"lastmodified\">");
            long lastModified = item.lastModified();
            if (lastModified > 0)
            {
                buf.append(dfmt.format(new Date(item.lastModified())));
            }
            buf.append("&nbsp;</td>");

            // Size
            buf.append("<td class=\"size\">");
            long length = item.length();
            if (length >= 0)
            {
                buf.append(String.format("%,d bytes", item.length()));
            }
            buf.append("&nbsp;</td></tr>\n");
        }
        buf.append("</tbody>\n");
        buf.append("</table>\n");
        buf.append("</body></html>\n");

        return buf.toString();
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
     * Encode any characters that could break the URI string in an HREF.
     * Such as <a href="/path/to;<script>Window.alert("XSS"+'%20'+"here");</script>">Link</a>
     *
     * The above example would parse incorrectly on various browsers as the "<" or '"' characters
     * would end the href attribute value string prematurely.
     *
     * @param raw the raw text to encode.
     * @return the defanged text.
     */
    private static String hrefEncodeURI(String raw)
    {
        StringBuffer buf = null;

        loop:
        for (int i = 0; i < raw.length(); i++)
        {
            char c = raw.charAt(i);
            switch (c)
            {
                case '\'':
                case '"':
                case '<':
                case '>':
                    buf = new StringBuffer(raw.length() << 1);
                    break loop;
                default:
                    break;
            }
        }
        if (buf == null)
            return raw;

        for (int i = 0; i < raw.length(); i++)
        {
            char c = raw.charAt(i);
            switch (c)
            {
                case '"':
                    buf.append("%22");
                    break;
                case '\'':
                    buf.append("%27");
                    break;
                case '<':
                    buf.append("%3C");
                    break;
                case '>':
                    buf.append("%3E");
                    break;
                default:
                    buf.append(c);
                    break;
            }
        }

        return buf.toString();
    }

    private static String deTag(String raw)
    {
        return StringUtil.sanitizeXmlString(raw);
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
        try (InputStream in = getInputStream();
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
     * Parse a list of String delimited resources and
     * return the List of Resources instances it represents.
     * <p>
     * Supports glob references that end in {@code /*} or {@code \*}.
     * Glob references will only iterate through the level specified and will not traverse
     * found directories within the glob reference.
     * </p>
     *
     * @param resources the comma {@code ,} or semicolon {@code ;} delimited
     * String of resource references.
     * @param globDirs true to return directories in addition to files at the level of the glob
     * @return the list of resources parsed from input string.
     */
    public static List<Resource> fromList(String resources, boolean globDirs) throws IOException
    {
        return fromList(resources, globDirs, Resource::newResource);
    }

    /**
     * Parse a delimited String of resource references and
     * return the List of Resources instances it represents.
     * <p>
     * Supports glob references that end in {@code /*} or {@code \*}.
     * Glob references will only iterate through the level specified and will not traverse
     * found directories within the glob reference.
     * </p>
     *
     * @param resources the comma {@code ,} or semicolon {@code ;} delimited
     * String of resource references.
     * @param globDirs true to return directories in addition to files at the level of the glob
     * @param resourceFactory the ResourceFactory used to create new Resource references
     * @return the list of resources parsed from input string.
     */
    public static List<Resource> fromList(String resources, boolean globDirs, ResourceFactory resourceFactory) throws IOException
    {
        if (StringUtil.isBlank(resources))
        {
            return Collections.emptyList();
        }

        List<Resource> returnedResources = new ArrayList<>();

        StringTokenizer tokenizer = new StringTokenizer(resources, StringUtil.DEFAULT_DELIMS);
        while (tokenizer.hasMoreTokens())
        {
            String token = tokenizer.nextToken().trim();

            // Is this a glob reference?
            if (token.endsWith("/*") || token.endsWith("\\*"))
            {
                String dir = token.substring(0, token.length() - 2);
                // Use directory
                Resource dirResource = resourceFactory.resolve(dir);
                if (dirResource.exists() && dirResource.isDirectory())
                {
                    // To obtain the list of entries
                    List<String> entries = dirResource.list();
                    if (entries != null)
                    {
                        entries.sort(Comparator.naturalOrder());
                        for (String entry : entries)
                        {
                            try
                            {
                                Resource resource = dirResource.resolve(entry);
                                if (!resource.isDirectory())
                                {
                                    returnedResources.add(resource);
                                }
                                else if (globDirs)
                                {
                                    returnedResources.add(resource);
                                }
                            }
                            catch (Exception ex)
                            {
                                LOG.warn("Bad glob [{}] entry: {}", token, entry, ex);
                            }
                        }
                    }
                }
            }
            else
            {
                // Simple reference, add as-is
                returnedResources.add(resourceFactory.resolve(token));
            }
        }

        return returnedResources;
    }

    public interface Mount extends Closeable
    {
        Resource root() throws IOException;
    }
}
