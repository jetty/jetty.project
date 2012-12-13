//
//  ========================================================================
//  Copyright (c) 1995-2012 Mort Bay Consulting Pty. Ltd.
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


package org.eclipse.jetty.maven.plugin;

import org.eclipse.jetty.server.ServerConnector;

/**
 * MavenServerConnector
 *
 *
 */
public class MavenServerConnector extends ServerConnector
{
    public static int DEFAULT_PORT = 8080;
    public static String DEFAULT_PORT_STR = String.valueOf(DEFAULT_PORT);   
    public static int DEFAULT_MAX_IDLE_TIME = 30000;
    
    public MavenServerConnector()
    {
        super(JettyServer.getInstance());
    }
}
