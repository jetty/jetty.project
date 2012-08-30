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


package org.eclipse.jetty.spring;

import java.util.Collection;


/* ------------------------------------------------------------ */
/**
 * Convenience class for jetty with Spring.
 * This class provides a setBeans method as an alternate
 * access to the {@link #addBean(Object)} API.
 */
public class Server extends org.eclipse.jetty.server.Server
{
    public void setBeans(Collection<Object> beans)
    {
        for (Object o:beans)
            addBean(o);
    }
    
}
