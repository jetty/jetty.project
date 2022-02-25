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

package org.eclipse.jetty.start;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLConnection;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashSet;
import java.util.Set;

/**
 * Interface for initializing a file resource.
 */
public abstract class FileInitializer
{
    protected final Set<String> _scheme = new HashSet<>();
    protected final BaseHome _basehome;

    protected FileInitializer(BaseHome basehome, String... scheme)
    {
        _basehome = basehome;
        if (scheme != null)
            for (String s : scheme)
            {
                _scheme.add(s.toLowerCase());
            }
    }

    public boolean isApplicable(URI uri)
    {
        if (_scheme.isEmpty())
            return uri == null;

        return uri != null && _scheme.contains(uri.getScheme().toLowerCase());
    }

    /**
     * Initialize a file resource
     *
     * @param uri the URI of the resource acting as its source
     * @param location the simple string reference to the output file, suitable for searching
     * for the file in other locations (like ${jetty.home} or ${jetty.dir})     *
     * @return true if local file system is modified.
     * @throws IOException if there was an attempt to initialize, but an error occurred.
     */

    public abstract boolean create(URI uri, String location) throws IOException;

    public boolean check(URI uri, String location) throws IOException
    {
        if (location != null)
        {
            // Process directly
            boolean isDir = location.endsWith("/");
            Path destination = getDestination(uri, location);

            if (FS.exists(destination))
            {
                // Validate existence
                if (isDir)
                {
                    if (!Files.isDirectory(destination))
                    {
                        throw new IOException("Invalid: path should be a directory (but isn't): " + location);
                    }
                    if (!FS.canReadDirectory(destination))
                    {
                        throw new IOException("Unable to read directory: " + location);
                    }
                }
                else
                {
                    if (!FS.canReadFile(destination))
                    {
                        throw new IOException("Unable to read file: " + location);
                    }
                }
                return true;
            }

            StartLog.error("Missing Required File: %s", _basehome.toShortForm(location));
            return false;
        }

        return true;
    }

    protected Path getDestination(URI uri, String location) throws IOException
    {
        if (location == null)
            return null;

        Path destination = _basehome.getBasePath(location);

        // now on copy/download paths (be safe above all else)
        if (destination != null && !destination.startsWith(_basehome.getBasePath()))
            throw new IOException("For security reasons, Jetty start is unable to process file resource not in ${jetty.base} - " + location);

        boolean isDestDir = Files.isDirectory(destination) || !Files.exists(destination) && location.endsWith("/");
        if (isDestDir && uri != null && uri.getSchemeSpecificPart().contains("/") && !uri.getSchemeSpecificPart().endsWith("/"))
            destination = destination.resolve(uri.getSchemeSpecificPart().substring(uri.getSchemeSpecificPart().lastIndexOf('/') + 1));

        return destination;
    }

    protected void download(URI uri, Path destination) throws IOException
    {
        if (FS.ensureDirectoryExists(destination.getParent()))
            StartLog.info("mkdir " + _basehome.toShortForm(destination.getParent()));

        StartLog.info("download %s to %s", uri, _basehome.toShortForm(destination));

        URLConnection connection = uri.toURL().openConnection();

        if (connection instanceof HttpURLConnection)
        {
            HttpURLConnection http = (HttpURLConnection)uri.toURL().openConnection();
            http.setInstanceFollowRedirects(true);
            http.setAllowUserInteraction(false);

            int status = http.getResponseCode();

            if (status != HttpURLConnection.HTTP_OK)
            {
                throw new IOException("URL GET Failure [" + status + "/" + http.getResponseMessage() + "] on " + uri);
            }
        }

        try (InputStream in = connection.getInputStream())
        {
            Files.copy(in, destination, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * Test if any of the Paths exist (as files)
     *
     * @param paths the list of paths to check
     * @return true if the path exist (as a file), false if it doesn't exist
     * @throws IOException if the path points to a non-file, or is not readable.
     */
    protected boolean isFilePresent(Path... paths) throws IOException
    {
        for (Path file : paths)
        {
            if (Files.exists(file))
            {
                if (Files.isDirectory(file))
                {
                    throw new IOException("Directory in the way: " + file.toAbsolutePath());
                }

                if (!Files.isReadable(file))
                {
                    throw new IOException("File not readable: " + file.toAbsolutePath());
                }

                return true;
            }
        }

        return false;
    }

    public boolean copyDirectory(Path source, Path destination) throws IOException
    {
        boolean modified = false;

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(source))
        {
            for (Path from : stream)
            {
                Path to = destination.resolve(from.getFileName());
                if (Files.isDirectory(from))
                {
                    if (FS.ensureDirectoryExists(to))
                    {
                        StartLog.info("mkdir " + _basehome.toShortForm(to));
                        modified = true;
                    }

                    if (copyDirectory(from, to))
                        modified = true;
                }
                else if (!Files.exists(to))
                {
                    StartLog.info("copy %s to %s", _basehome.toShortForm(from), _basehome.toShortForm(to));
                    Files.copy(from, to);
                    modified = true;
                }
            }
        }

        return modified;
    }
}
