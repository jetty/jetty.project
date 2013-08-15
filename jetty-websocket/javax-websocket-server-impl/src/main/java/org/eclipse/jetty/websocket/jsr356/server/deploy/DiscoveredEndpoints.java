//
//  ========================================================================
//  Copyright (c) 1995-2013 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.jsr356.server.deploy;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.HashSet;
import java.util.Set;

import javax.websocket.Endpoint;

import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

/**
 * Tracking for Discovered Endpoints.
 * <p>
 * This is a collection of endpoints, grouped by type (by Annotation or by extending Endpoint). Along with some better error messages for conflicting endpoints.
 */
public class DiscoveredEndpoints
{
    private static class LocatedClass
    {
        private Class<?> clazz;

        private URI location;
        public LocatedClass(Class<?> clazz)
        {
            this.clazz = clazz;
            this.location = getArchiveURI(clazz);
        }

        @Override
        public String toString()
        {
            StringBuilder builder = new StringBuilder();
            builder.append("LocatedClass[");
            builder.append(clazz.getName());
            builder.append("]");
            return builder.toString();
        }
    }

    private static final Logger LOG = Log.getLogger(DiscoveredEndpoints.class);

    public static URI getArchiveURI(Class<?> clazz)
    {
        String resourceName = clazz.getName().replace('.','/') + ".class";
        URL classUrl = clazz.getClassLoader().getResource(resourceName);
        if (classUrl == null)
        {
            // is this even possible at this point?
            return null;
        }
        try
        {
            URI uri = classUrl.toURI();
            String scheme = uri.getScheme();
            if (scheme.equalsIgnoreCase("jar"))
            {
                String ssp = uri.getSchemeSpecificPart();
                int idx = ssp.indexOf("!/");
                if (idx >= 0)
                {
                    ssp = ssp.substring(0,idx);
                }
                return URI.create(ssp);
            }
        }
        catch (URISyntaxException e)
        {
            LOG.warn("Class reference not a valid URI? " + classUrl,e);
        }

        return null;
    }
    private Set<LocatedClass> extendedEndpoints;

    private Set<LocatedClass> annotatedEndpoints;

    public DiscoveredEndpoints()
    {
        extendedEndpoints = new HashSet<>();
        annotatedEndpoints = new HashSet<>();
    }

    public void addAnnotatedEndpoint(Class<?> endpoint)
    {
        this.annotatedEndpoints.add(new LocatedClass(endpoint));
    }

    public void addExtendedEndpoint(Class<? extends Endpoint> endpoint)
    {
        this.extendedEndpoints.add(new LocatedClass(endpoint));
    }

    public Set<Class<?>> getAnnotatedEndpoints()
    {
        Set<Class<?>> endpoints = new HashSet<>();
        for (LocatedClass lc : annotatedEndpoints)
        {
            endpoints.add(lc.clazz);
        }
        return endpoints;
    }

    public void getArchiveSpecificAnnnotatedEndpoints(URI archiveURI, Set<Class<?>> archiveSpecificEndpoints)
    {
        for (LocatedClass lc : annotatedEndpoints)
        {
            if ((archiveURI == null) || lc.location.getPath().equals(archiveURI.getPath()))
            {
                archiveSpecificEndpoints.add(lc.clazz);
            }
        }
    }

    @SuppressWarnings("unchecked")
    public void getArchiveSpecificExtendedEndpoints(URI archiveURI, Set<Class<? extends Endpoint>> archiveSpecificEndpoints)
    {
        for (LocatedClass lc : extendedEndpoints)
        {
            if ((archiveURI == null) || (lc.location.getPath().equals(archiveURI.getPath()) && Endpoint.class.isAssignableFrom(lc.clazz)))
            {
                archiveSpecificEndpoints.add((Class<? extends Endpoint>)lc.clazz);
            }
        }
    }

    @Override
    public String toString()
    {
        StringBuilder builder = new StringBuilder();
        builder.append("DiscoveredEndpoints [extendedEndpoints=");
        builder.append(extendedEndpoints);
        builder.append(", annotatedEndpoints=");
        builder.append(annotatedEndpoints);
        builder.append("]");
        return builder.toString();
    }
}
