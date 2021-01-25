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

package org.eclipse.jetty.xml;

import java.net.MalformedURLException;
import java.net.URL;

import org.eclipse.jetty.util.resource.Resource;

/**
 * A ConfigurationProcessor for non XmlConfiguration format files.
 * <p>
 * A file in non-XmlConfiguration file format may be processed by a {@link ConfigurationProcessor}
 * instance that is returned from a {@link ConfigurationProcessorFactory} instance discovered by the
 * <code>ServiceLoader</code> mechanism.  This is used to allow spring configuration files to be used instead of
 * jetty.xml
 */
public interface ConfigurationProcessor
{
    /**
     * @deprecated use {@link #init(Resource, XmlParser.Node, XmlConfiguration)} instead
     */
    @Deprecated
    void init(URL url, XmlParser.Node root, XmlConfiguration configuration);

    /**
     * Initialize a ConfigurationProcessor from provided Resource and XML
     *
     * @param resource the resource being read
     * @param root the parsed XML root node for the resource
     * @param configuration the configuration being used (typically for ref IDs)
     */
    default void init(Resource resource, XmlParser.Node root, XmlConfiguration configuration)
    {
        // Moving back and forth between URL and File/FileSystem/Path/Resource is known to cause escaping issues.
        try
        {
            init(resource.getURI().toURL(), root, configuration);
        }
        catch (MalformedURLException e)
        {
            throw new IllegalStateException("Unable to convert Resource to URL", e);
        }
    }

    Object configure(Object obj) throws Exception;

    Object configure() throws Exception;
}
