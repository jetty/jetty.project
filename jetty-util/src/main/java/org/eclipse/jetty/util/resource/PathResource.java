//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
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
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.jetty.util.IO;
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
    
    private static final Path checkAliasPath(final Path path)
    {
        Path abs = path;
        if (!abs.isAbsolute())
        {
            abs = path.toAbsolutePath();
        }

        try
        {
            if (Files.isSymbolicLink(path))
                return Files.readSymbolicLink(path);
            if (Files.exists(path))
            {
                Path real = abs.toRealPath(FOLLOW_LINKS);
                if (!abs.equals(real))
                    return real;
            }
        }
        catch (NoSuchFileException e)
        {
            // Ignore
        }
        catch (Exception e)
        {
            // TODO: reevaluate severity level
            LOG.warn("bad alias ({}) for {}", e.getClass().getName(), e.getMessage());
            return abs;
        }
        return null;
    }

    public PathResource(File file)
    {
        this(file.toPath());
    }

    public PathResource(Path path)
    {
        this.path = path.toAbsolutePath();
        this.uri = this.path.toUri();
        this.alias = checkAliasPath(path);
    }

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
            path = new File(uri).toPath();
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
        this.alias = checkAliasPath(path);
    }

    public PathResource(URL url) throws IOException, URISyntaxException
    {
        this(url.toURI());
    }

    @Override
    public Resource addPath(final String subpath) throws IOException, MalformedURLException
    {
        String cpath = URIUtil.canonicalPath(subpath);

        if ((cpath == null) || (cpath.length() == 0))
            throw new MalformedURLException();

        if ("/".equals(cpath))
            return this;

        // subpaths are always under PathResource
        // compensate for input subpaths like "/subdir"
        // where default java.nio.file behavior would be
        // to treat that like an absolute path
        StringBuilder relpath = new StringBuilder();
        relpath.append(".").append(File.separator);
        relpath.append(cpath);
        return new PathResource(this.path.resolve(relpath.toString()).normalize());
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

    public Path getPath() throws IOException
    {
        return path;
    }

    @Override
    public InputStream getInputStream() throws IOException
    {
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
        return Files.isDirectory(path,NO_FOLLOW_LINKS);
    }

    @Override
    public long lastModified()
    {
        try
        {
            FileTime ft = Files.getLastModifiedTime(path,NO_FOLLOW_LINKS);
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
