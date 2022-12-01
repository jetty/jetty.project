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

package org.eclipse.jetty.ee9.osgi.annotations;

import java.io.File;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.Set;
import java.util.StringTokenizer;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

import org.eclipse.jetty.osgi.util.BundleFileLocatorHelperFactory;
import org.eclipse.jetty.util.FileID;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.util.resource.ResourceFactory;
import org.osgi.framework.Bundle;
import org.osgi.framework.Constants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 */
public class AnnotationParser extends org.eclipse.jetty.ee9.annotations.AnnotationParser
{
    private static final Logger LOG = LoggerFactory.getLogger(AnnotationParser.class);
    
    private Set<URI> _parsed = ConcurrentHashMap.newKeySet();

    private ConcurrentHashMap<URI, Bundle> _uriToBundle = new ConcurrentHashMap<>();
    private ConcurrentHashMap<Bundle, Resource> _bundleToResource = new ConcurrentHashMap<>();
    private ConcurrentHashMap<Resource, Bundle> _resourceToBundle = new ConcurrentHashMap<>();
    private ConcurrentHashMap<Bundle, URI> _bundleToUri = new ConcurrentHashMap<>();

    public AnnotationParser()
    {
        super();
    }

    /**
     * Keep track of a jetty URI Resource and its associated OSGi bundle.
     *
     *@param resourceFactory the ResourceFactory to convert bundle location
     * @param bundle the bundle to index
     * @return the resource for the bundle
     * @throws Exception if unable to create the resource reference
     */
    public Resource indexBundle(ResourceFactory resourceFactory, Bundle bundle) throws Exception
    {
        File bundleFile = BundleFileLocatorHelperFactory.getFactory().getHelper().getBundleInstallLocation(bundle);
        Resource resource = resourceFactory.newResource(bundleFile.toURI());
        URI uri = resource.getURI();
        _uriToBundle.putIfAbsent(uri, bundle);
        _bundleToUri.putIfAbsent(bundle, uri);
        _bundleToResource.putIfAbsent(bundle, resource);
        _resourceToBundle.putIfAbsent(resource, bundle);
        return resource;
    }

    protected URI getURI(Bundle bundle)
    {
        return _bundleToUri.get(bundle);
    }

    protected Resource getResource(Bundle bundle)
    {
        return _bundleToResource.get(bundle);
    }

    protected Bundle getBundle(Resource resource)
    {
        return _resourceToBundle.get(resource);
    }

    public void parse(Set<? extends Handler> handlers, Bundle bundle)
        throws Exception
    {

        Resource bundleResource = _bundleToResource.get(bundle);
        if (bundleResource == null)
            return;

        if (!_parsed.add(_bundleToUri.get(bundle)))
            return;


        parse(handlers, bundleResource);
    }
    
    @Override
    public void parse(final Set<? extends Handler> handlers, Resource r) throws Exception
    {
        if (r == null)
            return;

        if (FileID.isJavaArchive(r.getPath()))
        {
            parseJar(handlers, r);
            return;
        }

        if (r.isDirectory())
        {
            parseDir(handlers, r);
            return;
        }

        if (FileID.isClassFile(r.getPath()))
        {
            parseClass(handlers, null, r.getPath());
        }
        
        //Not already parsed, it could be a file that actually is compressed but does not have
        //.jar/.zip etc extension, such as equinox urls, so try to parse it
        try
        {
            parseJar(handlers, r);
        }
        catch (Exception e)
        {
            if (LOG.isDebugEnabled())
                LOG.warn("Resource not able to be scanned for classes: {}", r);
        }
    }
}
