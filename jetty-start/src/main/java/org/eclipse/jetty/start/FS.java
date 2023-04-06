//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
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
import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.FileSystemAlreadyExistsException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class FS
{
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

    public static String toRelativePath(File baseDir, File path)
    {
        return baseDir.toURI().relativize(path.toURI()).toASCIIString();
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
        String filename = archive.getFileName().toString().toLowerCase(Locale.US);

        if (filename.endsWith(".jar") || filename.endsWith(".zip"))
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
        StartLog.info("extract %s to %s", archive, destination);
        URI jaruri = URI.create("jar:" + archive.toUri());
        Map<String, ?> fsEnv = Map.of();
        try (FileSystem zipFs = FileSystems.newFileSystem(jaruri, fsEnv))
        {
            copyZipContents(zipFs.getPath("/"), destination);
        }
        catch (FileSystemAlreadyExistsException e)
        {
            FileSystem zipFs = FileSystems.getFileSystem(jaruri);
            copyZipContents(zipFs.getPath("/"), destination);
        }
    }

    public static void copyZipContents(Path root, Path destination) throws IOException
    {
        if (!Files.exists(destination))
        {
            Files.createDirectories(destination);
        }
        URI outputDirURI = destination.toUri();
        URI archiveURI = root.toUri();
        int archiveURISubIndex = archiveURI.toASCIIString().indexOf("!/") + 2;

        try (Stream<Path> entriesStream = Files.walk(root))
        {
            // ensure proper unpack order (eg: directories before files)
            Stream<Path> sorted = entriesStream
                .filter((path) -> path.getNameCount() > 0)
                .filter((path) -> !path.getName(0).toString().equalsIgnoreCase("META-INF"))
                .sorted();

            Iterator<Path> pathIterator = sorted.iterator();
            while (pathIterator.hasNext())
            {
                Path path = pathIterator.next();
                URI entryURI = path.toUri();
                String subURI = entryURI.toASCIIString().substring(archiveURISubIndex);
                URI outputPathURI = outputDirURI.resolve(subURI);
                Path outputPath = Path.of(outputPathURI);
                StartLog.info("zipFs: %s > %s", path, outputPath);
                if (Files.isDirectory(path))
                {
                    if (!Files.exists(outputPath))
                        Files.createDirectory(outputPath);
                }
                else if (Files.exists(outputPath))
                {
                    StartLog.debug("skipping extraction (file exists) %s", outputPath);
                }
                else
                {
                    StartLog.info("extracting %s to %s", path, outputPath);
                    Files.copy(path, outputPath);
                }
            }
        }
    }
}
