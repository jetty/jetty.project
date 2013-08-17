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
import java.util.regex.Pattern;

public class FS
{
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
            return pattern.matcher(path.getName()).matches();
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

    public static boolean isXml(String filename)
    {
        return Pattern.compile(".xml$",Pattern.CASE_INSENSITIVE).matcher(filename).matches();
    }

    public static boolean isFile(File file)
    {
        if (file == null)
        {
            return false;
        }
        return file.exists() && file.isFile();
    }

    public static void close(Closeable c)
    {
        if (c == null)
            return;

        try
        {
            c.close();
        }
        catch (IOException ignore)
        {
            /* ignore */
        }
    }
}
