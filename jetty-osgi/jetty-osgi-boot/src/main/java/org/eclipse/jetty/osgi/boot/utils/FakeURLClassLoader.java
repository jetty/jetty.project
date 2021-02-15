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

package org.eclipse.jetty.osgi.boot.utils;

import java.net.URL;
import java.net.URLClassLoader;

/**
 * FakeURLClassLoader
 * <p>
 * A URLClassloader that overrides the getURLs() method to return the list
 * of urls passed in to the constructor, but otherwise acts as if it has no
 * urls, which would cause it to delegate to the parent classloader (in this
 * case an OSGi classloader).
 * <p>
 * The main use of this class is with jars containing tlds. Jasper expects a
 * URL classloader to inspect for jars with tlds.
 */
public class FakeURLClassLoader extends URLClassLoader
{
    private URL[] _jars;

    public FakeURLClassLoader(ClassLoader osgiClassLoader, URL[] jars)
    {
        super(new URL[]{}, osgiClassLoader);
        _jars = jars;
    }

    /**
     * @return the jars that contains tlds so that TldLocationsCache or
     * TldScanner can find them.
     */
    @Override
    public URL[] getURLs()
    {
        return _jars;
    }

    /**
     * @see java.lang.Object#toString()
     */
    @Override
    public String toString()
    {
        StringBuilder builder = new StringBuilder();

        if (_jars != null)
        {
            for (URL u : _jars)
            {
                builder.append(" " + u.toString());
            }
            return builder.toString();
        }
        else
            return super.toString();
    }
}
