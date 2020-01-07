//
//  ========================================================================
//  Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
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

import org.eclipse.jetty.spring.SpringConfigurationProcessorFactory;
import org.eclipse.jetty.xml.ConfigurationProcessorFactory;

module org.eclipse.jetty.spring
{
    exports org.eclipse.jetty.spring;

    requires transitive org.eclipse.jetty.xml;
    requires spring.beans;
    requires spring.core;

    provides ConfigurationProcessorFactory with SpringConfigurationProcessorFactory;
}
