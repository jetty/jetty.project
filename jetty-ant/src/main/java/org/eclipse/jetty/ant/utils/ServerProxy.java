//
//  ========================================================================
//  Copyright (c) 1995-2012 Sabre Holdings.
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

package org.eclipse.jetty.ant.utils;

import org.eclipse.jetty.ant.AntWebAppContext;

public interface ServerProxy
{

    /**
     * Adds a new web application to this server.
     *
     * @param awc a AntWebAppContext object.
     */
    void addWebApplication(AntWebAppContext awc);

    /**
     * Starts this server.
     */
    void start();

    Object getProxiedObject();
}
