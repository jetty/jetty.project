//
//  ========================================================================
//  Copyright (c) 1995-2018 Mort Bay Consulting Pty. Ltd.
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
import java.net.URL;
import java.nio.channels.ReadableByteChannel;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystem;
import java.nio.file.FileSystemNotFoundException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

public class ZipFsResource extends Resource implements Iterable<String>
{
    private static final Logger LOG = Log.getLogger(ZipFsResource.class);
    /**
     * For a JVM that support JEP-238 Multi-Release jars, use "runtime" behavior.
     * On older JVMs that don't have JEP-238 support / behavior (eg: Java 1.8), this
     * FileSystem Environment variable is not used. (it's ignored)
     */
    private static final String MR_MODE_RUNTIME = "runtime";

    private final URI zipFsUri;
    private final Path zipFsPath;
    private final Path rootPath;
    private final Map<String, Object> env;
    private final FileSystem zipFs;

    public ZipFsResource(Path path) throws IOException
    {
        this(path.toUri(), MR_MODE_RUNTIME);
    }

    public ZipFsResource(File file) throws IOException
    {
        this(file.toURI(), MR_MODE_RUNTIME);
    }

    public ZipFsResource(URI uri) throws IOException
    {
        this(uri, MR_MODE_RUNTIME);
    }

    public ZipFsResource(URI uri, String multiReleaseMode) throws IOException
    {
        zipFsUri = toZipFsUri(uri);
        URI fileUri = URI.create(uri.getSchemeSpecificPart());
        zipFsPath = new File(fileUri.getSchemeSpecificPart()).toPath();

        // The FileSystem Environment
        env = new HashMap<>();
        env.put("multi-release", multiReleaseMode);

        // The ZipFs FileSystem
        FileSystem fs;
        try
        {
            fs = FileSystems.getFileSystem(zipFsUri);
        }
        catch (FileSystemNotFoundException ignore)
        {
            fs = null;
        }

        if ((fs == null) || !fs.isOpen())
        {
            fs = FileSystems.newFileSystem(zipFsUri, env);
        }

        zipFs = fs;

        // We only care about the 1 root that a zip/jar has
        Iterable<Path> rootDirs = zipFs.getRootDirectories();
        rootPath = rootDirs.iterator().next();
    }

    private final URI toZipFsUri(URI uri)
    {
        String asciiUri = uri.toASCIIString();

        if (asciiUri.equalsIgnoreCase("jar:file:") &&
                asciiUri.endsWith(".jar"))
        {
            // we have what we need already.
            return uri;
        }

        if ("file".equalsIgnoreCase(uri.getScheme()))
        {
            if (asciiUri.endsWith(".jar"))
            {
                return URI.create("jar:" + asciiUri);
            }
        }

        throw new UnsupportedOperationException(ZipFsResource.class.getSimpleName() + " only supports [jar:file:/] or [file:/] URIs that end with [.jar] - " + uri);
    }

    @Override
    public boolean isContainedIn(Resource resource) throws MalformedURLException
    {
        if (resource instanceof PathResource)
        {
            PathResource pathResource = (PathResource) resource;
            Path internalPath = zipFs.getPath(resource.getName());
            try
            {
                return Files.isSameFile(pathResource.getPath(), internalPath);
            }
            catch (IOException ignore)
            {
                LOG.ignore(ignore);
                return false;
            }
        }
        // all other resource types are not supported here
        return false;
    }

    @Override
    public void close()
    {
        try
        {
            zipFs.close();
        }
        catch (IOException ignore)
        {
            LOG.ignore(ignore);
        }
    }

    @Override
    public boolean exists()
    {
        // If we passed the constructor, this is known to exist
        return true;
    }

    @Override
    public boolean isDirectory()
    {
        // a ZipFsResource is always a directory (never a file)
        return true;
    }

    @Override
    public Iterator<String> iterator()
    {
        try
        {
            DirectoryStream<Path> dirStream = Files.newDirectoryStream(rootPath);
            return new PathToStringIterator(dirStream);
        }
        catch (IOException e)
        {
            LOG.warn("Unable to get iterator for: " + this, e);
            return Collections.emptyIterator();
        }
    }

    @Override
    public long lastModified()
    {
        try
        {
            return Files.getLastModifiedTime(zipFsPath).toMillis();
        }
        catch (IOException ignore)
        {
            LOG.ignore(ignore);
            return -1;
        }
    }

    @Override
    public long length()
    {
        try
        {
            return Files.size(zipFsPath);
        }
        catch (IOException ignore)
        {
            LOG.ignore(ignore);
            return -1;
        }
    }

    @Override
    public URL getURL()
    {
        try
        {
            return zipFsUri.toURL();
        }
        catch (MalformedURLException e)
        {
            throw new RuntimeException("Unable to get url for " + this);
        }
    }

    @Override
    public File getFile() throws IOException
    {
        return zipFsPath.toFile();
    }

    @Override
    public String getName()
    {
        return zipFsPath.toString();
    }

    @Override
    public InputStream getInputStream() throws IOException
    {
        throw new IOException("Cannot open InputStream for " + zipFs.provider().getClass().getName());
    }

    @Override
    public ReadableByteChannel getReadableByteChannel() throws IOException
    {
        throw new IOException("Cannot open ReadableByteChannel for " + zipFs.provider().getClass().getName());
    }

    @Override
    public boolean delete() throws SecurityException
    {
        throw new UnsupportedOperationException("Not supported by " + ZipFsResource.class.getSimpleName());
    }

    @Override
    public boolean renameTo(Resource resource) throws SecurityException
    {
        throw new UnsupportedOperationException("Not supported by " + ZipFsResource.class.getSimpleName());
    }

    @Override
    public String[] list()
    {
        ArrayList<String> entries = new ArrayList<>();
        iterator().forEachRemaining((entry) -> entries.add(entry));
        return entries.toArray(new String[0]);
    }

    @Override
    public Resource addPath(String s) throws IOException, MalformedURLException
    {
        Path path = rootPath.resolve(s);
        return new PathResource(path);
    }

    private static class PathToStringIterator implements Iterator<String>
    {
        private final Iterator<Path> pathIter;

        public PathToStringIterator(Iterable<Path> pathIterable)
        {
            this.pathIter = pathIterable.iterator();
        }

        @Override
        public boolean hasNext()
        {
            return pathIter.hasNext();
        }

        @Override
        public String next()
        {
            Path path = pathIter.next();
            return path.toString();
        }
    }
}
