//
//  ========================================================================
//  Copyright (c) 1995-2013 Mort Bay Consulting Pty. Ltd.
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
import java.util.Locale;
import java.util.regex.Pattern;

public class FS
{
    public static class AllFilter implements FileFilter
    {
        public static final AllFilter INSTANCE = new AllFilter();

        @Override
        public boolean accept(File pathname)
        {
            return true;
        }
    }

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

    public static class IniFilter extends FilenameRegexFilter
    {
        public IniFilter()
        {
            super("^.*\\.ini$");
        }
    }

    public static class XmlFilter extends FilenameRegexFilter
    {
        public XmlFilter()
        {
            super("^.*\\.xml$");
        }
    }

    public static boolean canReadDirectory(File path)
    {
        return (path.exists() && path.isDirectory() && path.canRead());
    }

    public static boolean canReadFile(File path)
    {
        return (path.exists() && path.isFile() && path.canRead());
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

    public static boolean isFile(File file)
    {
        if (file == null)
        {
            return false;
        }
        return file.exists() && file.isFile();
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
}
