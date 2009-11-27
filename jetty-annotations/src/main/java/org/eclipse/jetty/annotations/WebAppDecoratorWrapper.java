// ========================================================================
// Copyright (c) 2006-2009 Mort Bay Consulting Pty. Ltd.
// ------------------------------------------------------------------------
// All rights reserved. This program and the accompanying materials
// are made available under the terms of the Eclipse Public License v1.0
// and Apache License v2.0 which accompanies this distribution.
// The Eclipse Public License is available at 
// http://www.eclipse.org/legal/epl-v10.html
// The Apache License v2.0 is available at
// http://www.opensource.org/licenses/apache2.0.php
// You may elect to redistribute this code under either of these licenses. 
// ========================================================================

package org.eclipse.jetty.annotations;

import java.util.EventListener;

import javax.servlet.Filter;
import javax.servlet.Servlet;
import javax.servlet.ServletException;

import org.eclipse.jetty.plus.webapp.WebAppDecorator;
import org.eclipse.jetty.webapp.WebAppContext;

/**
 * WebAppDecoratorWrapper
 *
 *
 */
public class WebAppDecoratorWrapper extends WebAppDecorator
{
    WebAppDecorator _wrappedDecorator;
    AnnotationIntrospector _introspector = new AnnotationIntrospector();
    
    /**
     * @param context
     */
    public WebAppDecoratorWrapper(WebAppContext context, WebAppDecorator wrappedDecorator)
    {
        super(context);
        _wrappedDecorator = wrappedDecorator;
        _introspector.registerHandler(new ResourceAnnotationHandler(context));
        _introspector.registerHandler(new ResourcesAnnotationHandler(context));
        _introspector.registerHandler(new RunAsAnnotationHandler(context));
        _introspector.registerHandler(new PostConstructAnnotationHandler(context));
        _introspector.registerHandler(new PreDestroyAnnotationHandler(context));
        _introspector.registerHandler(new ServletSecurityAnnotationHandler(context)); 
    }


    public <T extends Filter> T filterCreated(T filter) throws ServletException
    {
        introspect(filter);
        return _wrappedDecorator.filterCreated(filter);
    }

 
    public <T extends EventListener> T listenerCreated(T listener) throws ServletException
    {
        introspect(listener);
        return _wrappedDecorator.listenerCreated(listener);
    }


    public <T extends Servlet> T servletCreated(T servlet) throws ServletException
    {
        introspect(servlet);
        return _wrappedDecorator.servletCreated(servlet);
    }
    
    

    public void destroyFilter(Filter f)
    {
        _wrappedDecorator.destroyFilter(f);
    }


    public void destroyListener(EventListener l)
    {
        _wrappedDecorator.destroyListener(l);
    }

 
    public void destroyServlet(Servlet s)
    {
        _wrappedDecorator.destroyServlet(s);
    }

  
    /**
     * Look for annotations that can be discovered with introspection:
     * <ul>
     * <li> Resource
     * <li> Resources
     * <li> PostConstruct
     * <li> PreDestroy
     * <li> ServletSecurity?
     * </ul>
     * @param o
     */
    protected void introspect (Object o)
    {
        _introspector.introspect(o.getClass());
    }
}
