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

package org.eclipse.jetty.ee9.maven.plugin;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;
import java.util.jar.Manifest;

import org.codehaus.plexus.util.SelectorUtils;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.URIUtil;
import org.eclipse.jetty.util.resource.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * SelectiveJarResource
 *
 * Selectively copies resources from a jar file based on includes/excludes.
 * TODO: investigate if copyTo() can instead have an IncludeExcludeSet as a parameter?
 * TODO: or have a smaller ResourceWrapper jetty-core class that can be overridden for specific behavior like in this class
 */
public class SelectiveJarResource extends Resource
{
    private static final Logger LOG = LoggerFactory.getLogger(SelectiveJarResource.class);
    
    /**
     * Default matches every resource.
     */
    public static final List<String> DEFAULT_INCLUDES = 
        Arrays.asList(new String[]{"**"});
    
    /**
     * Default is to exclude nothing.
     */
    public static final List<String> DEFAULT_EXCLUDES = Collections.emptyList();

    final Resource _delegate;
    List<String> _includes = null;
    List<String> _excludes = null;
    boolean _caseSensitive = false;

    public SelectiveJarResource(Resource resource)
    {
        _delegate = resource;
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
            if (SelectorUtils.matchPath(include, name, "/", _caseSensitive))
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
            if (SelectorUtils.matchPath(exclude, name, "/", _caseSensitive))
            {
                return true;
            }
        }
        return false;
    }

    @Override
    public Path getPath()
    {
        return _delegate.getPath();
    }

    @Override
    public boolean isContainedIn(Resource r)
    {
        return _delegate.isContainedIn(r);
    }

    @Override
    public URI getURI()
    {
        return _delegate.getURI();
    }

    @Override
    public String getName()
    {
        return _delegate.getName();
    }

    @Override
    public String getFileName()
    {
        return _delegate.getFileName();
    }

    @Override
    public Resource resolve(String subUriPath)
    {
        return _delegate.resolve(subUriPath);
    }

    @Override
    public void copyTo(Path directory) throws IOException
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

        String urlString = this.getURI().toASCIIString().trim();
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

                LOG.debug("Looking at {}", entryName);
                // make sure no access out of the root entry is present
                if (URIUtil.isNotNormalWithinSelf(entryName))
                {
                    LOG.info("Invalid entry: {}", entryName);
                    continue;
                }

                Path file = directory.resolve(entryName);

                if (entry.isDirectory())
                {
                    if (isIncluded(entryName))
                    {
                        if (!isExcluded(entryName))
                        {
                            // Make directory
                            if (!Files.exists(file))
                                Files.createDirectories(file);
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
                            Path dir = file.getParent();
                            if (!Files.exists(dir))
                                Files.createDirectories(dir);

                            // Make file
                            try (OutputStream fout = Files.newOutputStream(file))
                            {
                                IO.copy(jin, fout);
                            }

                            // touch the file.
                            if (entry.getTime() >= 0)
                                Files.setLastModifiedTime(file, FileTime.fromMillis(entry.getTime()));
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
                    Path metaInf = directory.resolve("META-INF");
                    Files.createDirectory(metaInf);
                    Path f = metaInf.resolve("MANIFEST.MF");
                    try (OutputStream fout = Files.newOutputStream(f))
                    {
                        manifest.write(fout);
                    }
                }
            }
        }
    }
}
