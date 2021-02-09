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

package org.eclipse.jetty.webapp;

import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Locale;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;
import java.util.regex.Pattern;

import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.resource.Resource;

/**
 * JarScannerConfiguration
 *
 * Abstract base class for configurations that want to scan jars in
 * WEB-INF/lib and the classloader hierarchy.
 *
 * Jar name matching based on regexp patterns is provided.
 *
 * Subclasses should implement the processEntry(URL jarUrl, JarEntry entry)
 * method to handle entries in jar files whose names match the supplied
 * pattern.
 *
 * @deprecated Does not handle MR Jars
 */
@Deprecated()
public abstract class JarScanner extends org.eclipse.jetty.util.PatternMatcher
{
    private static final Logger LOG = Log.getLogger(JarScanner.class);

    public abstract void processEntry(URI jarUri, JarEntry entry);

    /**
     * Find jar names from the provided list matching a pattern.
     *
     * If the pattern is null and isNullInclusive is true, then
     * all jar names will match.
     *
     * A pattern is a set of acceptable jar names. Each acceptable
     * jar name is a regex. Each regex can be separated by either a
     * "," or a "|". If you use a "|" this or's together the jar
     * name patterns. This means that ordering of the matches is
     * unimportant to you. If instead, you want to match particular
     * jar names, and you want to match them in order, you should
     * separate the regexs with "," instead.
     *
     * Eg "aaa-.*\\.jar|bbb-.*\\.jar"
     * Will iterate over the jar names and match
     * in any order.
     *
     * Eg "aaa-*\\.jar,bbb-.*\\.jar"
     * Will iterate over the jar names, matching
     * all those starting with "aaa-" first, then "bbb-".
     *
     * @param pattern the pattern to use for jar matching
     * @param uris the uris of the jars to scan
     * @param isNullInclusive if true, an empty pattern means all names match, if false, none match
     * @throws Exception if unable to scan
     */
    public void scan(Pattern pattern, URI[] uris, boolean isNullInclusive)
        throws Exception
    {
        super.match(pattern, uris, isNullInclusive);
    }

    /**
     * Find jar names from the classloader matching a pattern.
     *
     * If the pattern is null and isNullInclusive is true, then
     * all jar names in the classloader will match.
     *
     * A pattern is a set of acceptable jar names. Each acceptable
     * jar name is a regex. Each regex can be separated by either a
     * "," or a "|". If you use a "|" this or's together the jar
     * name patterns. This means that ordering of the matches is
     * unimportant to you. If instead, you want to match particular
     * jar names, and you want to match them in order, you should
     * separate the regexs with "," instead.
     *
     * Eg "aaa-.*\\.jar|bbb-.*\\.jar"
     * Will iterate over the jar names in the classloader and match
     * in any order.
     *
     * Eg "aaa-*\\.jar,bbb-.*\\.jar"
     * Will iterate over the jar names in the classloader, matching
     * all those starting with "aaa-" first, then "bbb-".
     *
     * If visitParent is true, then the pattern is applied to the
     * parent loader hierarchy. If false, it is only applied to the
     * classloader passed in.
     *
     * @param pattern the pattern to use for jar matching
     * @param loader the class loader to look for jars in
     * @param isNullInclusive if true, an empty pattern means all names match, if false, none match
     * @param visitParent if true, visit parent classloaders too
     * @throws Exception if unable to scan
     */
    public void scan(Pattern pattern, ClassLoader loader, boolean isNullInclusive, boolean visitParent)
        throws Exception
    {
        while (loader != null)
        {
            if (loader instanceof URLClassLoader)
            {
                URL[] urls = ((URLClassLoader)loader).getURLs();
                if (urls != null)
                {
                    URI[] uris = new URI[urls.length];
                    int i = 0;
                    for (URL u : urls)
                    {
                        uris[i++] = u.toURI();
                    }
                    scan(pattern, uris, isNullInclusive);
                }
            }
            if (visitParent)
                loader = loader.getParent();
            else
                loader = null;
        }
    }

    @Override
    public void matched(URI uri)
        throws Exception
    {
        LOG.debug("Search of {}", uri);
        if (uri.toString().toLowerCase(Locale.ENGLISH).endsWith(".jar"))
        {

            InputStream in = Resource.newResource(uri).getInputStream();
            if (in == null)
                return;

            JarInputStream jarIn = new JarInputStream(in);
            try
            {
                JarEntry entry = jarIn.getNextJarEntry();
                while (entry != null)
                {
                    processEntry(uri, entry);
                    entry = jarIn.getNextJarEntry();
                }
            }
            finally
            {
                jarIn.close();
            }
        }
    }
}
