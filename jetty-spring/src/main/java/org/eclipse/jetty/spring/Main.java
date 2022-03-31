//
//  ========================================================================
//  Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
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

import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.xml.XmlConfiguration;

/**
 * Runs Jetty from a Spring configuration file passed as argument.
 * @deprecated Has been removed in Jetty 10+
 */
@Deprecated
public class Main
{
    public static void main(String[] args) throws Exception
    {
        System.err.println("DEPRECATION WARNING - The `jetty-spring` project will see no further updates, and has been fully removed from Jetty 10 onwards");
        Resource config = Resource.newResource(args.length == 1 ? args[0] : "etc/jetty-spring.xml");
        XmlConfiguration.main(config.getFile().getAbsolutePath());
    }
}
