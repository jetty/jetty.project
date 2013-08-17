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

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Simple Start .INI handler
 */
public class StartIni implements Iterable<String>
{
    private final File file;
    private final LinkedList<String> lines;

    public StartIni(File file) throws FileNotFoundException, IOException
    {
        this.file = file;
        this.lines = new LinkedList<>();
        try (FileReader reader = new FileReader(file))
        {
            try (BufferedReader buf = new BufferedReader(reader))
            {
                String line;
                while ((line = buf.readLine()) != null)
                {
                    line = line.trim();
                    if (line.length() == 0)
                    {
                        // skip (empty line)
                        continue;
                    }
                    if (line.charAt(0) == '#')
                    {
                        // skip (comment)
                        continue;
                    }

                    // Smart Handling, split into multiple OPTIONS lines
                    if (line.startsWith("OPTIONS="))
                    {
                        for (String part : line.split(","))
                        {
                            lines.add("OPTIONS=" + part);
                        }
                    }
                    else
                    {
                        // Add line as-is
                        lines.add(line);
                    }
                }
            }
        }
    }

    public File getFile()
    {
        return file;
    }

    public int lineIndexOf(int offset, Pattern pattern)
    {
        int len = lines.size();
        for (int i = offset; i < len; i++)
        {
            if (pattern.matcher(lines.get(i)).matches())
            {
                return i;
            }
        }
        return -1;
    }

    public List<String> getLineMatches(Pattern pattern)
    {
        List<String> ret = new ArrayList<>();
        for (String line : lines)
        {
            if (pattern.matcher(line).matches())
            {
                ret.add(line);
            }
        }
        return ret;
    }

    public List<String> getLines()
    {
        return lines;
    }

    @Override
    public Iterator<String> iterator()
    {
        return lines.iterator();
    }

    public int overlayAt(int index, StartIni child)
    {
        int idx = index;
        int count = 0;
        for (String line : child)
        {
            if (this.hasLine(line))
            {
                // skip
                continue;
            }
            lines.add(idx++,line);
            count++;
        }
        return count;
    }

    private boolean hasLine(String line)
    {
        return lines.contains(line);
    }

    public void removeLine(String line)
    {
        lines.remove(line);
    }
}
