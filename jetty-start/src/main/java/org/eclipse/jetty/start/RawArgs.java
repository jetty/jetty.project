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

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import org.eclipse.jetty.start.RawArgs.Entry;

public class RawArgs implements Iterable<Entry>
{
    public class Entry
    {
        private String line;
        private String origin;

        private Entry(String line, String origin)
        {
            this.line = line;
            this.origin = origin;
        }

        public String getLine()
        {
            return line;
        }

        public String getOrigin()
        {
            return origin;
        }

        public boolean startsWith(String val)
        {
            return line.startsWith(val);
        }
    }

    /**
     * All of the args, in argument order
     */
    private List<Entry> args = new ArrayList<>();

    public void addAll(List<String> lines, Path sourceFile)
    {
        String source = sourceFile.toAbsolutePath().toString();
        for (String line : lines)
        {
            addArg(line, source);
        }
    }

    public void addArg(final String rawline, final String source)
    {
        if (rawline == null)
        {
            return;
        }

        String line = rawline.trim();
        if (line.length() == 0)
        {
            return;
        }

        args.add(new Entry(line, source));
    }

    @Override
    public Iterator<Entry> iterator()
    {
        return args.iterator();
    }

    public int size()
    {
        return args.size();
    }
}
