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

package org.eclipse.jetty.osgi;

import java.io.File;
import java.net.URI;
import java.util.concurrent.ConcurrentHashMap;

import org.eclipse.jetty.osgi.util.BundleFileLocatorHelperFactory;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.util.resource.ResourceFactory;
import org.osgi.framework.Bundle;

/**
 * Maintains a cross-reference between bundles and their corresponding jetty Resource representations.
 */
public class BundleIndex
{
    private final ConcurrentHashMap<URI, Bundle> _uriToBundle = new ConcurrentHashMap<>();
    private ConcurrentHashMap<Bundle, Resource> _bundleToResource = new ConcurrentHashMap<>();
    private ConcurrentHashMap<Resource, Bundle> _resourceToBundle = new ConcurrentHashMap<>();
    private ConcurrentHashMap<Bundle, URI> _bundleToUri = new ConcurrentHashMap<>();

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

    public URI getURI(Bundle bundle)
    {
        return _bundleToUri.get(bundle);
    }

    public Resource getResource(Bundle bundle)
    {
        return _bundleToResource.get(bundle);
    }

    public Bundle getBundle(Resource resource)
    {
        return _resourceToBundle.get(resource);
    }
}
