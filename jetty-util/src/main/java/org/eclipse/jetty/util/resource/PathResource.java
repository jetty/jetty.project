//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.channels.FileChannel;
import java.nio.channels.ReadableByteChannel;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;

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
    private final static LinkOption NO_FOLLOW_LINKS[] = new LinkOption[] { LinkOption.NOFOLLOW_LINKS };
    private final static LinkOption FOLLOW_LINKS[] = new LinkOption[] {};
    
    private final Path path;
    private final Path alias;
    private final URI uri;
    
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

        if(!URIUtil.equalsIgnoreEncodings(uri,path.toUri()))
        {
            return new File(uri).toPath().toAbsolutePath();
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
                
                /*
                 * If the real path is not the same as the absolute path
                 * then we know that the real path is the alias for the
                 * provided path.
                 *
                 * For OS's that are case insensitive, this should
                 * return the real (on-disk / case correct) version
                 * of the path.
                 *
                 * We have to be careful on Windows and OSX.
                 * 
                 * Assume we have the following scenario
                 *   Path a = new File("foo").toPath();
                 *   Files.createFile(a);
                 *   Path b = new File("FOO").toPath();
                 * 
                 * There now exists a file called "foo" on disk.
                 * Using Windows or OSX, with a Path reference of
                 * "FOO", "Foo", "fOO", etc.. means the following
                 * 
                 *                        |  OSX    |  Windows   |  Linux
                 * -----------------------+---------+------------+---------
                 * Files.exists(a)        |  True   |  True      |  True
                 * Files.exists(b)        |  True   |  True      |  False
                 * Files.isSameFile(a,b)  |  True   |  True      |  False
                 * a.equals(b)            |  False  |  True      |  False
                 * 
                 * See the javadoc for Path.equals() for details about this FileSystem
                 * behavior difference
                 * 
                 * We also cannot rely on a.compareTo(b) as this is roughly equivalent
                 * in implementation to a.equals(b)
                 */
                
                int absCount = abs.getNameCount();
                int realCount = real.getNameCount();
                if (absCount != realCount)
                {
                    // different number of segments
                    return real;
                }
                
                // compare each segment of path, backwards
                for (int i = realCount-1; i >= 0; i--)
                {
                    if (!abs.getName(i).toString().equals(real.getName(i).toString()))
                    {
                        return real;
                    }
                }
            }
        }
        catch (IOException e)
        {
            LOG.ignore(e);
        }
        catch (Exception e)
        {
            LOG.warn("bad alias ({} {}) for {}", e.getClass().getName(), e.getMessage(),path);
        }
        return null;
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
        this.path = path.toAbsolutePath();
        assertValidPath(path);
        this.uri = this.path.toUri();
        this.alias = checkAliasPath();
    }

    /**
     * Construct a new PathResource from a parent PathResource
     * and child sub path
     *
     * @param parent the parent path resource
     * @param childPath the child sub path
     */
    private PathResource(PathResource parent, String childPath) throws MalformedURLException
    {
        // Calculate the URI and the path separately, so that any aliasing done by
        // FileSystem.getPath(path,childPath) is visiable as a difference to the URI
        // obtained via URIUtil.addDecodedPath(uri,childPath)

        this.path = parent.path.getFileSystem().getPath(parent.path.toString(), childPath);
        if (isDirectory() &&!childPath.endsWith("/"))
            childPath+="/";
        this.uri = URIUtil.addDecodedPath(parent.uri,childPath);
        this.alias = checkAliasPath();
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
        catch (InvalidPathException e)
        {
            throw e;
        }
        catch (IllegalArgumentException e)
        {
            throw e;
        }
        catch (Exception e)
        {
            LOG.ignore(e);
            throw new IOException("Unable to build Path from: " + uri,e);
        }

        this.path = path.toAbsolutePath();
        this.uri = path.toUri();
        this.alias = checkAliasPath();
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
    public Resource addPath(final String subpath) throws IOException, MalformedURLException
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
        if(idx >= 0)
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
            if (other.path != null)
            {
                return false;
            }
        }
        else if (!path.equals(other.path))
        {
            return false;
        }
        return true;
    }

    @Override
    public boolean exists()
    {
        return Files.exists(path,NO_FOLLOW_LINKS);
    }

    @Override
    public File getFile() throws IOException
    {
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
        /* Mimic behavior from old FileResource class and its
         * usage of java.io.FileInputStream(File) which will trigger
         * an IOException on construction if the path is a directory
         */
        if (Files.isDirectory(path))
            throw new IOException(path + " is a directory");

        return Files.newInputStream(path,StandardOpenOption.READ);
    }

    @Override
    public String getName()
    {
        return path.toAbsolutePath().toString();
    }

    @Override
    public ReadableByteChannel getReadableByteChannel() throws IOException
    {
        return FileChannel.open(path,StandardOpenOption.READ);
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
        result = (prime * result) + ((path == null)?0:path.hashCode());
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
        return Files.isDirectory(path,FOLLOW_LINKS);
    }

    @Override
    public long lastModified()
    {
        try
        {
            FileTime ft = Files.getLastModifiedTime(path,FOLLOW_LINKS);
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
        return this.alias!=null;
    }

    /**
     * The Alias as a Path.
     * <p>
     *     Note: this cannot return the alias as a DIFFERENT path in 100% of situations,
     *     due to Java's internal Path/File normalization.
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
        return this.alias==null?null:this.alias.toUri();
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
                Path result = Files.move(path,destRes.path);
                return Files.exists(result,NO_FOLLOW_LINKS);
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
            IO.copyDir(this.path.toFile(),destination);
        }
        else
        {
            Files.copy(this.path,destination.toPath());
        }
    }

    @Override
    public String toString()
    {
        return this.uri.toASCIIString();
    }
}