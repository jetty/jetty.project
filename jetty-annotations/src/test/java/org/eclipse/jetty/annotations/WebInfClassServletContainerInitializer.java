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

package org.eclipse.jetty.annotations;

import java.util.Set;
import javax.servlet.ServletContainerInitializer;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;

/**
 * ServerServletContainerInitializer
 */
public class WebInfClassServletContainerInitializer implements ServletContainerInitializer
{

    /**
     *
     */
    public WebInfClassServletContainerInitializer()
    {
        // TODO Auto-generated constructor stub
    }

    /**
     * @see javax.servlet.ServletContainerInitializer#onStartup(java.util.Set, javax.servlet.ServletContext)
     */
    @Override
    public void onStartup(Set<Class<?>> c, ServletContext ctx) throws ServletException
    {
        // TODO Auto-generated method stub

    }
}
