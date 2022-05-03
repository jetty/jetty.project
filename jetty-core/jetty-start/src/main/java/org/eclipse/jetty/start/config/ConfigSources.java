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

package org.eclipse.jetty.start.config;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.ListIterator;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.jetty.start.FS;
import org.eclipse.jetty.start.Props;
import org.eclipse.jetty.start.Props.Prop;
import org.eclipse.jetty.start.RawArgs;
import org.eclipse.jetty.start.UsageException;

import static org.eclipse.jetty.start.UsageException.ERR_BAD_ARG;

/**
 * Weighted List of ConfigSources.
 */
public class ConfigSources implements Iterable<ConfigSource>
{
    private static class WeightedConfigSourceComparator implements Comparator<ConfigSource>
    {
        @Override
        public int compare(ConfigSource o1, ConfigSource o2)
        {
            return o1.getWeight() - o2.getWeight();
        }
    }

    private LinkedList<ConfigSource> sources = new LinkedList<>();
    private AtomicInteger sourceWeight = new AtomicInteger(1);

    public void add(ConfigSource source) throws IOException
    {
        if (sources.contains(source))
        {
            // TODO: needs a better/more clear error message
            throw new UsageException(ERR_BAD_ARG, "Duplicate Configuration Source Reference: " + source);
        }
        sources.add(source);
        Collections.sort(sources, new WeightedConfigSourceComparator());

        // look for --include-jetty-dir entries
        for (RawArgs.Entry arg : source.getArgs())
        {
            if (arg.startsWith("--include-jetty-dir"))
            {
                String ref = getValue(arg.getLine());
                String dirName = getProps().expand(ref);
                Path dir = FS.toPath(dirName).normalize().toAbsolutePath();
                DirConfigSource dirsource = new DirConfigSource(ref, dir, sourceWeight.incrementAndGet(), true);
                add(dirsource);
            }
        }
    }

    public CommandLineConfigSource getCommandLineSource()
    {
        for (ConfigSource source : sources)
        {
            if (source instanceof CommandLineConfigSource)
            {
                return (CommandLineConfigSource)source;
            }
        }
        return null;
    }

    public Prop getProp(String key)
    {
        return getProps().getProp(key);
    }

    public Props getProps()
    {
        Props props = new Props();

        // add all properties from config sources (in reverse order)
        ListIterator<ConfigSource> iter = sources.listIterator(sources.size());
        while (iter.hasPrevious())
        {
            ConfigSource source = iter.previous();
            props.addAll(source.getProps());
        }
        return props;
    }

    private String getValue(String arg)
    {
        int idx = arg.indexOf('=');
        if (idx == (-1))
        {
            throw new UsageException(ERR_BAD_ARG, "Argument is missing a required value: %s", arg);
        }
        String value = arg.substring(idx + 1).trim();
        if (value.length() <= 0)
        {
            throw new UsageException(ERR_BAD_ARG, "Argument is missing a required value: %s", arg);
        }
        return value;
    }

    @Override
    public Iterator<ConfigSource> iterator()
    {
        return sources.iterator();
    }

    public ListIterator<ConfigSource> reverseListIterator()
    {
        return sources.listIterator(sources.size());
    }

    @Override
    public String toString()
    {
        StringBuilder str = new StringBuilder();
        str.append(this.getClass().getSimpleName());
        str.append('[');
        boolean delim = false;
        for (ConfigSource source : sources)
        {
            if (delim)
            {
                str.append(',');
            }
            str.append(source.getId());
            delim = true;
        }
        str.append(']');
        return str.toString();
    }
}
