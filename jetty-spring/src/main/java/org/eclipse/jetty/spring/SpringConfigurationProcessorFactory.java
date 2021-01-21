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

package org.eclipse.jetty.spring;

import org.eclipse.jetty.xml.ConfigurationProcessor;
import org.eclipse.jetty.xml.ConfigurationProcessorFactory;
import org.eclipse.jetty.xml.XmlConfiguration;

/**
 * Spring ConfigurationProcessor Factory
 * <p>
 * Create a {@link SpringConfigurationProcessor} for XML documents with a "beans" element.
 * The factory is discovered by a {@link java.util.ServiceLoader} for {@link ConfigurationProcessorFactory}.
 *
 * @see SpringConfigurationProcessor
 * @see XmlConfiguration
 */
public class SpringConfigurationProcessorFactory implements ConfigurationProcessorFactory
{
    @Override
    public ConfigurationProcessor getConfigurationProcessor(String dtd, String tag)
    {
        if ("beans".equals(tag))
            return new SpringConfigurationProcessor();
        return null;
    }
}
