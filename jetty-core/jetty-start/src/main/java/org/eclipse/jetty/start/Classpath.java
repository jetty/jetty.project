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
import java.io.PrintStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.StringTokenizer;
import java.util.stream.Collectors;

/**
 * Class to handle CLASSPATH construction
 */
public class Classpath implements Iterable<Path>
{
    private static class Loader extends URLClassLoader
    {
        static
        {
            registerAsParallelCapable();
        }

        Loader(URL[] urls, ClassLoader parent)
        {
            super(urls, parent);
        }

        @Override
        public String toString()
        {
            return "startJarLoader@" + Long.toHexString(hashCode());
        }
    }

    private final List<Path> elements = new ArrayList<>();

    public Classpath()
    {
    }

    public Classpath(String initial)
    {
        addClasspath(initial);
    }

    public boolean addClasspath(String s)
    {
        boolean added = false;
        if (s != null)
        {
            StringTokenizer t = new StringTokenizer(s, FS.pathSeparator());
            while (t.hasMoreTokens())
            {
                added |= addComponent(t.nextToken());
            }
        }
        return added;
    }

    public boolean addComponent(Path file)
    {
        StartLog.debug("Adding classpath component: %s", file);
        if ((file == null) || (!Files.exists(file)))
        {
            // not a valid component
            return false;
        }

        try
        {
            Path key = file.toRealPath();
            if (!elements.contains(key))
            {
                elements.add(key);
                return true;
            }
        }
        catch (IOException e)
        {
            StartLog.debug(e);
        }

        return false;
    }

    public boolean addComponent(String component)
    {
        if ((component == null) || (component.length() <= 0))
        {
            // nothing to add
            return false;
        }

        return addComponent(Paths.get(component));
    }

    public int count()
    {
        return elements.size();
    }

    public void dump(PrintStream out)
    {
        int i = 0;
        for (Path element : elements)
        {
            out.printf("%2d: %s%n", i++, element);
        }
    }

    public ClassLoader getClassLoader()
    {
        int cnt = elements.size();

        URL[] urls = new URL[cnt];
        for (int i = 0; i < cnt; i++)
        {
            try
            {
                urls[i] = elements.get(i).toUri().toURL();
                StartLog.debug("URLClassLoader.url[%d] = %s", i, urls[i]);
            }
            catch (MalformedURLException e)
            {
                StartLog.warn(e);
            }
        }
        StartLog.debug("Loaded %d URLs into URLClassLoader", urls.length);

        ClassLoader parent = Thread.currentThread().getContextClassLoader();
        if (parent == null)
        {
            parent = Classpath.class.getClassLoader();
        }
        if (parent == null)
        {
            parent = ClassLoader.getSystemClassLoader();
        }
        return new Loader(urls, parent);
    }

    public List<Path> getElements()
    {
        return elements;
    }

    public boolean isEmpty()
    {
        return (elements == null) || (elements.isEmpty());
    }

    @Override
    public Iterator<Path> iterator()
    {
        return elements.iterator();
    }

    /**
     * Overlay another classpath, copying its elements into place on this Classpath, while eliminating duplicate entries on the classpath.
     *
     * @param other the other classpath to overlay
     */
    public void overlay(Classpath other)
    {
        for (Path otherElement : other.elements)
        {
            if (this.elements.contains(otherElement))
            {
                // Skip duplicate entries
                continue;
            }
            this.elements.add(otherElement);
        }
    }

    @Override
    public String toString()
    {
        return elements.stream()
            .map(Path::toString)
            .collect(Collectors.joining(FS.pathSeparator()));
    }
}
