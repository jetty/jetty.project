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

package org.eclipse.jetty.ee.osgi.annotations;

import java.net.URI;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.eclipse.jetty.osgi.BundleIndex;
import org.eclipse.jetty.util.FileID;
import org.eclipse.jetty.util.resource.Resource;
import org.osgi.framework.Bundle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Extension of {@link org.eclipse.jetty.annotations.AnnotationParser} to parse
 * classes inside OSGi bundles.
 */
public class AnnotationParser extends org.eclipse.jetty.annotations.AnnotationParser
{
    private static final Logger LOG = LoggerFactory.getLogger(AnnotationParser.class);
    
    private final Set<URI> _parsed = ConcurrentHashMap.newKeySet();
    private final BundleIndex _bundleIndex = new BundleIndex();

    public AnnotationParser()
    {
        super();
    }
    
    public AnnotationParser(int platform)
    {
        super(platform);
    }

    public BundleIndex getBundleIndex()
    {
        return _bundleIndex;
    }

    public void parse(Set<? extends Handler> handlers, Bundle bundle)
        throws Exception
    {

        Resource bundleResource = _bundleIndex.getResource(bundle);
        if (bundleResource == null)
            return;

        //if already added, it is already parsed
        if (!_parsed.add(_bundleIndex.getURI(bundle)))
            return;

        parse(handlers, bundleResource);
    }

    @Override
    public void parse(final Set<? extends Handler> handlers, Resource r) throws Exception
    {
        if (r == null)
            return;
        
        if (!r.exists())
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
