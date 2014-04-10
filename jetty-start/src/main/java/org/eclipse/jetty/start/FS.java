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

package org.eclipse.jetty.start;

import java.io.Closeable;
import java.io.File;
import java.io.FileFilter;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Locale;
import java.util.regex.Pattern;

public class FS
{
    @Deprecated
    public static class AllFilter implements FileFilter
    {
        public static final AllFilter INSTANCE = new AllFilter();

        @Override
        public boolean accept(File pathname)
        {
            return true;
        }
    }

    @Deprecated
    public static class DirFilter implements FileFilter
    {
        public static final DirFilter INSTANCE = new DirFilter();

        @Override
        public boolean accept(File path)
        {
            return path.isDirectory();
        }
    }

    @Deprecated
    public static class FilenameRegexFilter implements FileFilter
    {
        private final Pattern pattern;

        public FilenameRegexFilter(String regex)
        {
            pattern = Pattern.compile(regex,Pattern.CASE_INSENSITIVE);
        }

        @Override
        public boolean accept(File path)
        {
            return path.isFile() && pattern.matcher(path.getName()).matches();
        }
    }

    @Deprecated
    public static class FileNamesFilter implements FileFilter
    {
        private final String filenames[];

        public FileNamesFilter(String... names)
        {
            this.filenames = names;
        }

        @Override
        public boolean accept(File path)
        {
            if (!path.isFile())
            {
                return false;
            }
            for (String name : filenames)
            {
                if (name.equalsIgnoreCase(path.getName()))
                {
                    return true;
                }
            }
            return false;
        }
    }

    @Deprecated
    public static class IniFilter extends FilenameRegexFilter
    {
        public IniFilter()
        {
            super("^.*\\.ini$");
        }
    }

    @Deprecated
    public static class RelativeRegexFilter implements FileFilter
    {
        private final File baseDir;
        private final Pattern pattern;

        public RelativeRegexFilter(File baseDir, Pattern pattern)
        {
            this.baseDir = baseDir;
            this.pattern = pattern;
        }

        @Override
        public boolean accept(File path)
        {
            // get relative path
            String relativePath = FS.toRelativePath(baseDir,path);

            // see if it matches
            return (pattern.matcher(relativePath).matches());
        }
    }

    @Deprecated
    public static class XmlFilter extends FilenameRegexFilter
    {
        public XmlFilter()
        {
            super("^.*\\.xml$");
        }
    }

    // Default Link Options
    private static final LinkOption[] NO_LINK_OPTIONS = new LinkOption[0];

    public static boolean canReadDirectory(File path)
    {
        return (path.exists() && path.isDirectory() && path.canRead());
    }

    public static boolean canReadDirectory(Path path)
    {
        return Files.exists(path,NO_LINK_OPTIONS) && Files.isDirectory(path,NO_LINK_OPTIONS) && Files.isReadable(path);
    }

    public static boolean canReadFile(File path)
    {
        return (path.exists() && path.isFile() && path.canRead());
    }

    public static boolean canReadFile(Path path)
    {
        return Files.exists(path,NO_LINK_OPTIONS) && Files.isRegularFile(path,NO_LINK_OPTIONS) && Files.isReadable(path);
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
        return Files.exists(ret,NO_LINK_OPTIONS);
    }

    /**
     * @deprecated use {@link #ensureDirectoryExists(Path)} instead
     */
    @Deprecated
    public static void ensureDirectoryExists(File dir) throws IOException
    {
        if (dir.exists())
        {
            return;
        }
        if (!dir.mkdirs())
        {
            throw new IOException("Unable to create directory: " + dir.getAbsolutePath());
        }
    }
    
    public static void ensureDirectoryExists(Path dir) throws IOException
    {
        if (exists(dir))
        {
            // exists already, nothing to do
            return;
        }
        Files.createDirectories(dir);
    }

    /**
     * @deprecated use {@link #ensureDirectoryWritable(Path)} instead
     */
    @Deprecated
    public static void ensureDirectoryWritable(File dir) throws IOException
    {
        if (!dir.exists())
        {
            throw new IOException("Directory does not exist: " + dir.getAbsolutePath());
        }
        if (!dir.canWrite())
        {
            throw new IOException("Unable to write to directory: " + dir.getAbsolutePath());
        }
    }

    public static void ensureDirectoryWritable(Path dir) throws IOException
    {
        if (!Files.exists(dir,NO_LINK_OPTIONS))
        {
            throw new IOException("Path does not exist: " + dir.toAbsolutePath());
        }
        if (!Files.isDirectory(dir,NO_LINK_OPTIONS))
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
        return Files.exists(path,NO_LINK_OPTIONS);
    }

    public static boolean isFile(File file)
    {
        if (file == null)
        {
            return false;
        }
        return file.exists() && file.isFile();
    }

    public static boolean isValidDirectory(Path path)
    {
        LinkOption lopts[] = NO_LINK_OPTIONS;
        if (!Files.exists(path,lopts))
        {
            // doesn't exist, not a valid directory
            return false;
        }

        if (!Files.isDirectory(path,lopts))
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

    public static Path toOptionalPath(String path)
    {
        if (path == null)
        {
            return null;
        }
        return toPath(path);
    }

    public static Path toPath(String path)
    {
        return FileSystems.getDefault().getPath(FS.separators(path));
    }

    public static String toRelativePath(File baseDir, File path)
    {
        return baseDir.toURI().relativize(path.toURI()).toASCIIString();
    }

    public static void touch(Path path) throws IOException
    {
        FileTime now = FileTime.fromMillis(System.currentTimeMillis());
        Files.setLastModifiedTime(path,now);
    }

    public static Path toRealPath(Path path) throws IOException
    {
        return path.toRealPath(NO_LINK_OPTIONS);
    }
}
