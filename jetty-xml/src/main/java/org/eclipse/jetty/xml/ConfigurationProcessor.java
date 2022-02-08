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
