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

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Enumeration;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.eclipse.jetty.util.FileID;

public class FS
{
    public static String separator()
    {
        return FileSystems.getDefault().getSeparator();
    }

    public static String pathSeparator()
    {
        return File.pathSeparator;
    }

    public static boolean canReadDirectory(Path path)
    {
        return Files.exists(path) && Files.isDirectory(path) && Files.isReadable(path);
    }

    public static boolean canReadFile(Path path)
    {
        return Files.exists(path) && Files.isRegularFile(path) && Files.isReadable(path);
    }

    public static boolean canWrite(Path path)
    {
        return Files.isWritable(path);
    }

    public static void close(Closeable c)
    {
        if (c == null)
        {
            return;
        }

        try
        {
            c.close();
        }
        catch (IOException ignore)
        {
            /* ignore */
        }
    }

    public static boolean createNewFile(Path path) throws IOException
    {
        Path ret = Files.createFile(path);
        return Files.exists(ret);
    }

    public static boolean ensureDirectoryExists(Path dir) throws IOException
    {
        if (exists(dir))
        {
            // Is it a directory?
            if (!Files.isDirectory(dir))
                throw new IOException("Path is not directory: " + dir.toAbsolutePath());

            // exists already, nothing to do
            return false;
        }
        Files.createDirectories(dir);
        return true;
    }

    public static void ensureDirectoryWritable(Path dir) throws IOException
    {
        if (!Files.exists(dir))
        {
            throw new IOException("Path does not exist: " + dir.toAbsolutePath());
        }
        if (!Files.isDirectory(dir))
        {
            throw new IOException("Directory does not exist: " + dir.toAbsolutePath());
        }
        if (!Files.isWritable(dir))
        {
            throw new IOException("Unable to write to directory: " + dir.toAbsolutePath());
        }
    }

    public static boolean exists(Path path)
    {
        return Files.exists(path);
    }

    public static boolean isValidDirectory(Path path)
    {
        if (!Files.exists(path))
        {
            // doesn't exist, not a valid directory
            return false;
        }

        if (!Files.isDirectory(path))
        {
            // not a directory (as expected)
            StartLog.warn("Not a directory: " + path);
            return false;
        }

        return true;
    }

    public static boolean isXml(String filename)
    {
        return filename.toLowerCase(Locale.ENGLISH).endsWith(".xml");
    }

    public static String toRelativePath(Path baseDir, Path path)
    {
        return baseDir.toUri().relativize(path.toUri()).toASCIIString();
    }

    public static boolean isPropertyFile(String filename)
    {
        return filename.toLowerCase(Locale.ENGLISH).endsWith(".properties");
    }

    public static String separators(String path)
    {
        StringBuilder ret = new StringBuilder();
        for (char c : path.toCharArray())
        {
            if ((c == '/') || (c == '\\'))
            {
                ret.append(File.separatorChar);
            }
            else
            {
                ret.append(c);
            }
        }
        return ret.toString();
    }

    public static Path toPath(String path)
    {
        return FileSystems.getDefault().getPath(FS.separators(path));
    }

    public static void touch(Path path) throws IOException
    {
        FileTime now = FileTime.fromMillis(System.currentTimeMillis());
        Files.setLastModifiedTime(path, now);
    }

    public static Path toRealPath(Path path) throws IOException
    {
        return path.toRealPath();
    }

    public static void extract(Path archive, Path destination) throws IOException
    {
        if (FileID.isLibArchive(archive))
        {
            extractZip(archive, destination);
        }
        else
        {
            throw new IOException("Unable to extract unsupported archive format: " + archive);
        }
    }

    public static void extractZip(Path archive, Path destination) throws IOException
    {
        try (ZipFile zip = new ZipFile(archive.toFile()))
        {
            StartLog.info("extract %s to %s", archive, destination);
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements())
            {
                ZipEntry entry = entries.nextElement();

                if (entry.isDirectory() || entry.getName().startsWith("/META-INF"))
                {
                    // skip
                    continue;
                }

                Path destFile = destination.resolve(entry.getName());
                if (!Files.exists(destFile))
                {
                    FS.ensureDirectoryExists(destFile.getParent());
                    try (InputStream input = zip.getInputStream(entry))
                    {
                        StartLog.debug("extracting %s", destFile);
                        Files.copy(input, destFile);
                    }
                }
                else
                {
                    StartLog.debug("skipping extract (file exists) %s", destFile);
                }
            }
        }
    }
}
