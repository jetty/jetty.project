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

package org.eclipse.jetty.test;

import java.util.Set;
import javax.servlet.ServletContainerInitializer;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;

/**
 * A SCI that tosses an Error to intentionally to cause issues with the DeploymentManager
 *
 * @see <a href="https://github.com/eclipse/jetty.project/issues/1602">Issue #1602</a>
 */
public class DeploymentErrorInitializer implements ServletContainerInitializer
{
    @Override
    public void onStartup(Set<Class<?>> c, ServletContext ctx) throws ServletException
    {
        throw new NoClassDefFoundError("Intentional.Failure");
    }
}
