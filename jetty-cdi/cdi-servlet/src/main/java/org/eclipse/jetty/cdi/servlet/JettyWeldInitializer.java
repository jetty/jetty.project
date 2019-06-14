//
//  ========================================================================
//  Copyright (c) 1995-2019 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.cdi.servlet;

import javax.naming.NamingException;
import javax.naming.Reference;

import org.eclipse.jetty.plus.jndi.Resource;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.webapp.ClasspathPattern;
import org.eclipse.jetty.webapp.WebAppContext;

/**
 * Utility class suitable for initializing CDI/Weld on Embedded Jetty
 */
public class JettyWeldInitializer
{
    /**
     * Initialize WebAppContext to support CDI/Weld.
     * <p>
     * Initializes Context, then sets up WebAppContext system and server classes to allow Weld to operate from Server
     * level.
     * <p>
     * Includes {@link #initContext(ContextHandler)} behavior as well.
     * @param webapp the webapp
     * @throws NamingException if unable to bind BeanManager context
     */
    public static void initWebApp(WebAppContext webapp) throws NamingException
    {
        initContext(webapp);

        // webapp cannot change / replace weld classes
        ClasspathPattern systemClasses = webapp.getSystemClasspathPattern();
        systemClasses.add("org.jboss.weld.");
        systemClasses.add("org.jboss.classfilewriter.");
        systemClasses.add("org.jboss.logging.");
        systemClasses.add("com.google.common.");
        systemClasses.add("org.eclipse.jetty.cdi.websocket.annotation.");

        // don't hide weld classes from webapps (allow webapp to use ones from system classloader)
        ClasspathPattern serverClasses = webapp.getServerClasspathPattern();
        serverClasses.add("-org.eclipse.jetty.cdi.websocket.annotation.");
        serverClasses.add("-org.eclipse.jetty.cdi.core.");
        serverClasses.add("-org.eclipse.jetty.cdi.servlet.");
        serverClasses.add("-org.jboss.weld.");
        serverClasses.add("-org.jboss.classfilewriter.");
        serverClasses.add("-org.jboss.logging.");
        serverClasses.add("-com.google.common.");
    }

    public static void initContext(ContextHandler handler) throws NamingException
    {
        // Add context specific weld container reference.
        // See https://issues.jboss.org/browse/WELD-1710
        // and https://github.com/weld/core/blob/2.2.5.Final/environments/servlet/core/src/main/java/org/jboss/weld/environment/servlet/WeldServletLifecycle.java#L244-L253
        handler.setInitParameter("org.jboss.weld.environment.container.class","org.jboss.weld.environment.jetty.JettyContainer");

        // Setup Weld BeanManager reference
        Reference ref = new Reference("javax.enterprise.inject.spi.BeanManager","org.jboss.weld.resources.ManagerObjectFactory",null);
        new Resource(handler,"BeanManager",ref);
    }
}
