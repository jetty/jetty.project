//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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

import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.StringTokenizer;

/**
 * Class to handle CLASSPATH construction
 */
public class Classpath implements Iterable<File>
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

    private final List<File> elements = new ArrayList<File>();

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
            StringTokenizer t = new StringTokenizer(s, File.pathSeparator);
            while (t.hasMoreTokens())
            {
                added |= addComponent(t.nextToken());
            }
        }
        return added;
    }

    public boolean addComponent(File path)
    {
        StartLog.debug("Adding classpath component: %s", path);
        if ((path == null) || (!path.exists()))
        {
            // not a valid component
            return false;
        }

        try
        {
            File key = path.getCanonicalFile();
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

        return addComponent(new File(component));
    }

    public int count()
    {
        return elements.size();
    }

    public void dump(PrintStream out)
    {
        int i = 0;
        for (File element : elements)
        {
            out.printf("%2d: %s%n", i++, element.getAbsolutePath());
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
                urls[i] = elements.get(i).toURI().toURL();
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

    public List<File> getElements()
    {
        return elements;
    }

    public boolean isEmpty()
    {
        return (elements == null) || (elements.isEmpty());
    }

    @Override
    public Iterator<File> iterator()
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
        for (File otherElement : other.elements)
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
        StringBuffer cp = new StringBuffer(1024);
        boolean needDelim = false;
        for (File element : elements)
        {
            if (needDelim)
            {
                cp.append(File.pathSeparatorChar);
            }
            cp.append(element.getAbsolutePath());
            needDelim = true;
        }
        return cp.toString();
    }
}
