//
//  ========================================================================
//  Copyright (c) 1995-2019 Mort Bay Consulting Pty. Ltd.
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

import org.eclipse.jetty.util.resource.Resource;

/**
 * A ConfigurationProcessor for non XmlConfiguration format files.
 * <p>
 * A file in non-XmlConfiguration file format may be processed by a {@link ConfigurationProcessor}
 * instance that is returned from a {@link ConfigurationProcessorFactory} instance discovered by the
 * {@code ServiceLoader} mechanism.  This is used to allow spring configuration files to be used instead of
 * {@code jetty.xml}
 */
public interface ConfigurationProcessor
{
    /**
     * Initialize a ConfigurationProcessor from provided Resource and XML
     *
     * @param resource the resource being read
     * @param root the parsed XML root node for the resource
     * @param configuration the configuration being used (typically for ref IDs)
     */
    void init(Resource resource, XmlParser.Node root, XmlConfiguration configuration);

    Object configure(Object obj) throws Exception;

    Object configure() throws Exception;
}
