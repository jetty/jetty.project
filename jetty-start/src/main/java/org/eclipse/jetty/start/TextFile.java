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

package org.eclipse.jetty.start;

import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Simple common abstraction for Text files, that consist of a series of lines.
 * <p>
 * Ignoring lines that are empty, deemed to be comments, or are duplicates of prior lines.
 */
public class TextFile implements Iterable<String>
{
    private final Path file;
    private final List<String> lines = new ArrayList<>();
    private final List<String> allLines = new ArrayList<>();

    public TextFile(Path file) throws FileNotFoundException, IOException
    {
        this.file = file;
        init();
        
        if (!FS.canReadFile(file))
        {
            StartLog.debug("Skipping read of missing file: %s",file.toAbsolutePath());
            return;
        }

        try (BufferedReader buf = Files.newBufferedReader(file,StandardCharsets.UTF_8))
        {
            String line;
            while ((line = buf.readLine()) != null)
            {
                if (line.length() == 0)
                {
                    continue;
                }

                allLines.add(line);
                
                if (line.charAt(0) == '#')
                {
                    continue;
                }

                // TODO - bad form calling derived method from base class constructor
                process(line.trim());
            }
        }
    }

    public void addUniqueLine(String line)
    {
        if (lines.contains(line))
        {
            // skip
            return;
        }
        lines.add(line);
    }

    public Path getFile()
    {
        return file;
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

    public List<String> getAllLines()
    {
        return allLines;
    }

    public void init()
    {
    }

    public Stream<String> stream()
    {
        return lines.stream();
    }
    
    @Override
    public Iterator<String> iterator()
    {
        return lines.iterator();
    }
    
    public ListIterator<String> listIterator()
    {
        return lines.listIterator();
    }

    public void process(String line)
    {
        addUniqueLine(line);
    }
    
    @Override 
    public String toString()
    {
        return file.toString();
    }
}
