//
//  ========================================================================
//  Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.util.resource;

import java.io.File;
import java.io.IOError;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.URIUtil;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

/**
 * Java NIO Path equivalent of FileResource.
 */
public class PathResource extends Resource
{
    private static final Logger LOG = Log.getLogger(PathResource.class);
    private static final LinkOption[] NO_FOLLOW_LINKS = new LinkOption[]{LinkOption.NOFOLLOW_LINKS};
    private static final LinkOption[] FOLLOW_LINKS = new LinkOption[]{};

    private final Path path;
    private final Path alias;
    private final URI uri;
    private final boolean belongsToDefaultFileSystem;

    private final Path checkAliasPath()
    {
        Path abs = path;

        /* Catch situation where the Path class has already normalized
         * the URI eg. input path "aa./foo.txt"
         * from an #addPath(String) is normalized away during
         * the creation of a Path object reference.
         * If the URI is different then the Path.toUri() then
         * we will just use the original URI to construct the
         * alias reference Path.
         */

        if (!URIUtil.equalsIgnoreEncodings(uri, path.toUri()))
        {
            try
            {
                return Paths.get(uri).toRealPath(FOLLOW_LINKS);
            }
            catch (IOException ignored)
            {
                // If the toRealPath() call fails, then let
                // the alias checking routines continue on
                // to other techniques.
                LOG.ignore(ignored);
            }
        }

        if (!abs.isAbsolute())
        {
            abs = path.toAbsolutePath();
        }

        try
        {
            if (Files.isSymbolicLink(path))
                return path.getParent().resolve(Files.readSymbolicLink(path));
            if (Files.exists(path))
            {
                Path real = abs.toRealPath(FOLLOW_LINKS);

                if (!isSameName(abs, real))
                {
                    return real;
                }
            }
        }
        catch (IOException e)
        {
            LOG.ignore(e);
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
     * Construct a new PathResource from a File object.
     * <p>
     * An invocation of this convenience constructor of the form.
     * </p>
     * <pre>
     * new PathResource(file);
     * </pre>
     * <p>
     * behaves in exactly the same way as the expression
     * </p>
     * <pre>
     * new PathResource(file.toPath());
     * </pre>
     *
     * @param file the file to use
     */
    public PathResource(File file)
    {
        this(file.toPath());
    }

    /**
     * Construct a new PathResource from a Path object.
     *
     * @param path the path to use
     */
    public PathResource(Path path)
    {
        Path absPath = path;
        try
        {
            absPath = path.toAbsolutePath();
        }
        catch (IOError ioError)
        {
            // Not able to resolve absolute path from provided path
            // This could be due to a glob reference, or a reference
            // to a path that doesn't exist (yet)
            if (LOG.isDebugEnabled())
                LOG.debug("Unable to get absolute path for {}", path, ioError);
        }

        // cleanup any lingering relative path nonsense (like "/./" and "/../")
        this.path = absPath.normalize();

        assertValidPath(path);
        this.uri = this.path.toUri();
        this.alias = checkAliasPath();
        this.belongsToDefaultFileSystem = this.path.getFileSystem() == FileSystems.getDefault();
    }

    /**
     * Construct a new PathResource from a parent PathResource
     * and child sub path
     *
     * @param parent the parent path resource
     * @param childPath the child sub path
     */
    private PathResource(PathResource parent, String childPath)
    {
        // Calculate the URI and the path separately, so that any aliasing done by
        // FileSystem.getPath(path,childPath) is visible as a difference to the URI
        // obtained via URIUtil.addDecodedPath(uri,childPath)

        this.path = parent.path.getFileSystem().getPath(parent.path.toString(), childPath);
        if (isDirectory() && !childPath.endsWith("/"))
            childPath += "/";
        this.uri = URIUtil.addPath(parent.uri, childPath);
        this.alias = checkAliasPath();
        this.belongsToDefaultFileSystem = this.path.getFileSystem() == FileSystems.getDefault();
    }

    /**
     * Construct a new PathResource from a URI object.
     * <p>
     * Must be an absolute URI using the <code>file</code> scheme.
     *
     * @param uri the URI to build this PathResource from.
     * @throws IOException if unable to construct the PathResource from the URI.
     */
    public PathResource(URI uri) throws IOException
    {
        if (!uri.isAbsolute())
        {
            throw new IllegalArgumentException("not an absolute uri");
        }

        if (!uri.getScheme().equalsIgnoreCase("file"))
        {
            throw new IllegalArgumentException("not file: scheme");
        }

        Path path;
        try
        {
            path = Paths.get(uri);
        }
        catch (IllegalArgumentException e)
        {
            throw e;
        }
        catch (Exception e)
        {
            LOG.ignore(e);
            throw new IOException("Unable to build Path from: " + uri, e);
        }

        this.path = path.toAbsolutePath();
        this.uri = path.toUri();
        this.alias = checkAliasPath();
        this.belongsToDefaultFileSystem = this.path.getFileSystem() == FileSystems.getDefault();
    }

    /**
     * Create a new PathResource from a provided URL object.
     * <p>
     * An invocation of this convenience constructor of the form.
     * </p>
     * <pre>
     * new PathResource(url);
     * </pre>
     * <p>
     * behaves in exactly the same way as the expression
     * </p>
     * <pre>
     * new PathResource(url.toURI());
     * </pre>
     *
     * @param url the url to attempt to create PathResource from
     * @throws IOException if URL doesn't point to a location that can be transformed to a PathResource
     * @throws URISyntaxException if the provided URL was malformed
     */
    public PathResource(URL url) throws IOException, URISyntaxException
    {
        this(url.toURI());
    }

    @Override
    public Resource addPath(final String subpath) throws IOException
    {
        String cpath = URIUtil.canonicalPath(subpath);

        if ((cpath == null) || (cpath.length() == 0))
            throw new MalformedURLException(subpath);

        if ("/".equals(cpath))
            return this;

        // subpaths are always under PathResource
        // compensate for input subpaths like "/subdir"
        // where default resolve behavior would be
        // to treat that like an absolute path

        return new PathResource(this, subpath);
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
        // not applicable for FileSytem / Path
    }

    @Override
    public boolean delete() throws SecurityException
    {
        try
        {
            return Files.deleteIfExists(path);
        }
        catch (IOException e)
        {
            LOG.ignore(e);
            return false;
        }
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
            return other.path == null;
        }
        else
            return path.equals(other.path);
    }

    @Override
    public boolean exists()
    {
        return Files.exists(path, NO_FOLLOW_LINKS);
    }

    @Override
    public File getFile() throws IOException
    {
        if (!belongsToDefaultFileSystem)
            return null;
        return path.toFile();
    }

    /**
     * @return the {@link Path} of the resource
     */
    public Path getPath()
    {
        return path;
    }

    @Override
    public InputStream getInputStream() throws IOException
    {
        return Files.newInputStream(path, StandardOpenOption.READ);
    }

    @Override
    public String getName()
    {
        return path.toAbsolutePath().toString();
    }

    @Override
    public ReadableByteChannel getReadableByteChannel() throws IOException
    {
        return newSeekableByteChannel();
    }

    public SeekableByteChannel newSeekableByteChannel() throws IOException
    {
        return Files.newByteChannel(path, StandardOpenOption.READ);
    }

    @Override
    public URI getURI()
    {
        return this.uri;
    }

    @Override
    public URL getURL()
    {
        try
        {
            return path.toUri().toURL();
        }
        catch (MalformedURLException e)
        {
            return null;
        }
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
    public boolean isContainedIn(Resource r) throws MalformedURLException
    {
        // not applicable for FileSystem / path
        return false;
    }

    @Override
    public boolean isDirectory()
    {
        return Files.isDirectory(path, FOLLOW_LINKS);
    }

    @Override
    public long lastModified()
    {
        try
        {
            FileTime ft = Files.getLastModifiedTime(path, FOLLOW_LINKS);
            return ft.toMillis();
        }
        catch (IOException e)
        {
            LOG.ignore(e);
            return 0;
        }
    }

    @Override
    public long length()
    {
        try
        {
            return Files.size(path);
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
    public String[] list()
    {
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
            int size = entries.size();
            return entries.toArray(new String[size]);
        }
        catch (DirectoryIteratorException e)
        {
            LOG.debug(e);
        }
        catch (IOException e)
        {
            LOG.debug(e);
        }
        return null;
    }

    @Override
    public boolean renameTo(Resource dest) throws SecurityException
    {
        if (dest instanceof PathResource)
        {
            PathResource destRes = (PathResource)dest;
            try
            {
                Path result = Files.move(path, destRes.path);
                return Files.exists(result, NO_FOLLOW_LINKS);
            }
            catch (IOException e)
            {
                LOG.ignore(e);
                return false;
            }
        }
        else
        {
            return false;
        }
    }

    @Override
    public void copyTo(File destination) throws IOException
    {
        if (isDirectory())
        {
            IO.copyDir(this.path.toFile(), destination);
        }
        else
        {
            Files.copy(this.path, destination.toPath());
        }
    }

    /**
     * @param outputStream the output stream to write to
     * @param start First byte to write
     * @param count Bytes to write or -1 for all of them.
     * @throws IOException if unable to copy the Resource to the output
     */
    @Override
    public void writeTo(OutputStream outputStream, long start, long count)
        throws IOException
    {
        long length = count;

        if (count < 0)
        {
            length = Files.size(path) - start;
        }

        try (SeekableByteChannel channel = Files.newByteChannel(path, StandardOpenOption.READ))
        {
            ByteBuffer buffer = BufferUtil.allocate(IO.bufferSize);
            skipTo(channel, buffer, start);

            // copy from channel to output stream
            long readTotal = 0;
            while (readTotal < length)
            {
                BufferUtil.clearToFill(buffer);
                int size = (int)Math.min(IO.bufferSize, length - readTotal);
                buffer.limit(size);
                int readLen = channel.read(buffer);
                BufferUtil.flipToFlush(buffer, 0);
                BufferUtil.writeTo(buffer, outputStream);
                readTotal += readLen;
            }
        }
    }

    private void skipTo(SeekableByteChannel channel, ByteBuffer buffer, long skipTo) throws IOException
    {
        try
        {
            if (channel.position() != skipTo)
            {
                channel.position(skipTo);
            }
        }
        catch (UnsupportedOperationException e)
        {
            final int NO_PROGRESS_LIMIT = 3;

            if (skipTo > 0)
            {
                long pos = 0;
                long readLen;
                int noProgressLoopLimit = NO_PROGRESS_LIMIT;
                // loop till we reach desired point, break out on lack of progress.
                while (noProgressLoopLimit > 0 && pos < skipTo)
                {
                    BufferUtil.clearToFill(buffer);
                    int len = (int)Math.min(IO.bufferSize, (skipTo - pos));
                    buffer.limit(len);
                    readLen = channel.read(buffer);
                    if (readLen == 0)
                    {
                        noProgressLoopLimit--;
                    }
                    else if (readLen > 0)
                    {
                        pos += readLen;
                        noProgressLoopLimit = NO_PROGRESS_LIMIT;
                    }
                    else
                    {
                        // negative values means the stream was closed or reached EOF
                        // either way, we've hit a state where we can no longer
                        // fulfill the requested range write.
                        throw new IOException("EOF reached before SeekableByteChannel skip destination");
                    }
                }

                if (noProgressLoopLimit <= 0)
                {
                    throw new IOException("No progress made to reach SeekableByteChannel skip position " + skipTo);
                }
            }
        }
    }

    @Override
    public String toString()
    {
        return this.uri.toASCIIString();
    }
}
