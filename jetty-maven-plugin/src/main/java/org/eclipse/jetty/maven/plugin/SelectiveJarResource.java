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

package org.eclipse.jetty.maven.plugin;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;
import java.util.jar.Manifest;

import org.codehaus.plexus.util.SelectorUtils;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.URIUtil;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.resource.JarResource;

/**
 * SelectiveJarResource
 *
 * Selectively copies resources from a jar file based on includes/excludes.
 */
public class SelectiveJarResource extends JarResource
{
    private static final Logger LOG = Log.getLogger(SelectiveJarResource.class);
    public static final List<String> DEFAULT_INCLUDES = Arrays.asList("**"); // No includes supplied, so set it to 'matches all'
    public static final List<String> DEFAULT_EXCLUDES = Collections.emptyList(); //No includes, set to no exclusions

    List<String> _includes = null;
    List<String> _excludes = null;
    boolean _caseSensitive = false;

    public SelectiveJarResource(URL url)
    {
        super(url);
    }

    public SelectiveJarResource(URL url, boolean useCaches)
    {
        super(url, useCaches);
    }

    public void setCaseSensitive(boolean caseSensitive)
    {
        _caseSensitive = caseSensitive;
    }

    public void setIncludes(List<String> patterns)
    {
        _includes = patterns;
    }

    public void setExcludes(List<String> patterns)
    {
        _excludes = patterns;
    }

    protected boolean isIncluded(String name)
    {
        for (String include : _includes)
        {
            if (SelectorUtils.matchPath(include, name, _caseSensitive))
            {
                return true;
            }
        }
        return false;
    }

    protected boolean isExcluded(String name)
    {
        for (String exclude : _excludes)
        {
            if (SelectorUtils.matchPath(exclude, name, _caseSensitive))
            {
                return true;
            }
        }
        return false;
    }

    /**
     * @see org.eclipse.jetty.util.resource.JarResource#copyTo(java.io.File)
     */
    @Override
    public void copyTo(File directory) throws IOException
    {
        if (_includes == null)
            _includes = DEFAULT_INCLUDES;
        if (_excludes == null)
            _excludes = DEFAULT_EXCLUDES;

        //Copy contents of the jar file to the given directory, 
        //using the includes and excludes patterns to control which
        //parts of the jar file are copied
        if (!exists())
            return;

        String urlString = this.getURL().toExternalForm().trim();
        int endOfJarUrl = urlString.indexOf("!/");
        int startOfJarUrl = (endOfJarUrl >= 0 ? 4 : 0);

        if (endOfJarUrl < 0)
            throw new IOException("Not a valid jar url: " + urlString);

        URL jarFileURL = new URL(urlString.substring(startOfJarUrl, endOfJarUrl));

        try (InputStream is = jarFileURL.openConnection().getInputStream();
             JarInputStream jin = new JarInputStream(is))
        {
            JarEntry entry;

            while ((entry = jin.getNextJarEntry()) != null)
            {
                String entryName = entry.getName();

                LOG.debug("Looking at " + entryName);
                String dotCheck = StringUtil.replace(entryName, '\\', '/');
                dotCheck = URIUtil.canonicalPath(dotCheck);
                if (dotCheck == null)
                {
                    LOG.info("Invalid entry: " + entryName);
                    continue;
                }

                File file = new File(directory, entryName);

                if (entry.isDirectory())
                {
                    if (isIncluded(entryName))
                    {
                        if (!isExcluded(entryName))
                        {
                            // Make directory
                            if (!file.exists())
                                file.mkdirs();
                        }
                        else
                            LOG.debug("{} dir is excluded", entryName);
                    }
                    else
                        LOG.debug("{} dir is NOT included", entryName);
                }
                else
                {
                    //entry is a file, is it included?
                    if (isIncluded(entryName))
                    {
                        if (!isExcluded(entryName))
                        {
                            // make directory (some jars don't list dirs)
                            File dir = new File(file.getParent());
                            if (!dir.exists())
                                dir.mkdirs();

                            // Make file
                            try (OutputStream fout = new FileOutputStream(file))
                            {
                                IO.copy(jin, fout);
                            }

                            // touch the file.
                            if (entry.getTime() >= 0)
                                file.setLastModified(entry.getTime());
                        }
                        else
                            LOG.debug("{} file is excluded", entryName);
                    }
                    else
                        LOG.debug("{} file is NOT included", entryName);
                }
            }

            Manifest manifest = jin.getManifest();
            if (manifest != null)
            {
                if (isIncluded("META-INF") && !isExcluded("META-INF"))
                {
                    File metaInf = new File(directory, "META-INF");
                    metaInf.mkdir();
                    File f = new File(metaInf, "MANIFEST.MF");
                    try (OutputStream fout = new FileOutputStream(f))
                    {
                        manifest.write(fout);
                    }
                }
            }
        }
    }
}
