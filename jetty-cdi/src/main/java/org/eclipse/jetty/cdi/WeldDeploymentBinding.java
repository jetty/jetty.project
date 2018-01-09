//
//  ========================================================================
//  Copyright (c) 1995-2018 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.cdi;

import javax.naming.Reference;

import org.eclipse.jetty.deploy.App;
import org.eclipse.jetty.deploy.AppLifeCycle;
import org.eclipse.jetty.deploy.graph.Node;
import org.eclipse.jetty.plus.jndi.Resource;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.webapp.WebAppContext;

/**
 * Perform some basic weld configuration of WebAppContext
 */
public class WeldDeploymentBinding implements AppLifeCycle.Binding
{
    public String[] getBindingTargets()
    {
        return new String[]
        { "deploying" };
    }

    public void processBinding(Node node, App app) throws Exception
    {
        ContextHandler handler = app.getContextHandler();
        if (handler == null)
        {
            throw new NullPointerException("No Handler created for App: " + app);
        }

        if (handler instanceof WebAppContext)
        {
            WebAppContext webapp = (WebAppContext)handler;

            // Add context specific weld container reference.
            // See https://issues.jboss.org/browse/WELD-1710
            // and https://github.com/weld/core/blob/2.2.5.Final/environments/servlet/core/src/main/java/org/jboss/weld/environment/servlet/WeldServletLifecycle.java#L244-L253
            webapp.setInitParameter("org.jboss.weld.environment.container.class",
                    "org.jboss.weld.environment.jetty.JettyContainer");
            
            // Setup Weld BeanManager reference
            Reference ref = new Reference("javax.enterprise.inject.spi.BeanManager",
                    "org.jboss.weld.resources.ManagerObjectFactory", null);
            new Resource(webapp,"BeanManager",ref);
            
            // webapp cannot change / replace weld classes
            webapp.addSystemClass("org.jboss.weld.");
            webapp.addSystemClass("org.jboss.classfilewriter.");
            webapp.addSystemClass("org.jboss.logging.");
            webapp.addSystemClass("com.google.common.");
            
            // don't hide weld classes from webapps (allow webapp to use ones from system classloader)
            webapp.addServerClass("-org.jboss.weld.");
            webapp.addServerClass("-org.jboss.classfilewriter.");
            webapp.addServerClass("-org.jboss.logging.");
            webapp.addServerClass("-com.google.common.");
        }
    }
}
