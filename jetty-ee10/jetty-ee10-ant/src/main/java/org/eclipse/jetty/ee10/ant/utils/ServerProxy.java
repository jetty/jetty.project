//
//  ========================================================================
//  Copyright (c) 1995-2012 Sabre Holdings and others.
//  ------------------------------------------------------------------------
//  This program and the accompanying materials are made available under the
//  terms of the Eclipse Public License v. 2.0 which is available at
//  https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
//  which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
//  SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
//  ========================================================================
//

package org.eclipse.jetty.ee9.ant.utils;

import org.eclipse.jetty.ee9.ant.AntWebAppContext;

public interface ServerProxy
{

    /**
     * Adds a new web application to this server.
     *
     * @param awc a AntWebAppContext object.
     */
    public void addWebApplication(AntWebAppContext awc);

    /**
     * Starts this server.
     */
    public void start();

    public Object getProxiedObject();
}
