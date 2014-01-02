//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
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

package com.acme;
import java.util.EventListener;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import javax.servlet.ServletContextAttributeEvent;
import javax.servlet.ServletContextAttributeListener;
import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;
import javax.servlet.ServletRequestAttributeEvent;
import javax.servlet.ServletRequestAttributeListener;
import javax.servlet.ServletRequestEvent;
import javax.servlet.ServletRequestListener;
import javax.servlet.annotation.WebListener;
import javax.servlet.http.HttpSessionActivationListener;
import javax.servlet.http.HttpSessionAttributeListener;
import javax.servlet.http.HttpSessionBindingEvent;
import javax.servlet.http.HttpSessionEvent;
import javax.servlet.http.HttpSessionIdListener;
import javax.servlet.http.HttpSessionListener;


@Foo(1)
@WebListener
public class TestListener implements HttpSessionListener,  HttpSessionAttributeListener, HttpSessionActivationListener, ServletContextListener, ServletContextAttributeListener, ServletRequestListener, ServletRequestAttributeListener
{
    public static class NaughtyServletContextListener implements ServletContextListener
    {

        @Override
        public void contextInitialized(ServletContextEvent sce)
        {
            throw new IllegalStateException("Should not call NaughtServletContextListener.contextInitialized");
        }

        @Override
        public void contextDestroyed(ServletContextEvent sce)
        {
            throw new IllegalStateException("Should not call NaughtServletContextListener.contextDestroyed");
        }
    }
    
    public static class InvalidListener implements EventListener
    {
        public InvalidListener()
        {}
    }
    
    public static class ValidListener implements HttpSessionIdListener
    {
        @Resource(mappedName="maxAmount")
        private Double maxAmount;
        
        public ValidListener()
        {}
        
        @Override
        public void sessionIdChanged(HttpSessionEvent event, String oldSessionId)
        {
           
        }
        
    }

    @Resource(mappedName="maxAmount")
    private Double maxAmount;
    


    public void attributeAdded(HttpSessionBindingEvent se)
    {
        // System.err.println("attributedAdded "+se);
    }

    public void attributeRemoved(HttpSessionBindingEvent se)
    {
        // System.err.println("attributeRemoved "+se);
    }

    public void attributeReplaced(HttpSessionBindingEvent se)
    {
        // System.err.println("attributeReplaced "+se);
    }

    public void sessionWillPassivate(HttpSessionEvent se)
    {
        // System.err.println("sessionWillPassivate "+se);
    }

    public void sessionDidActivate(HttpSessionEvent se)
    {
        // System.err.println("sessionDidActivate "+se);
    }

    public void contextInitialized(ServletContextEvent sce)
    {
        System.err.println("Calling TestListener.contextInitialized");
        
        sce.getServletContext().setAttribute("com.acme.AnnotationTest.sclInjectTest", Boolean.valueOf(maxAmount != null));
        
        //Can't add a ServletContextListener from a ServletContextListener even if it is declared in web.xml
        try
        {
            sce.getServletContext().addListener(new NaughtyServletContextListener());
            sce.getServletContext().setAttribute("com.acme.AnnotationTest.sclFromSclRegoTest", Boolean.FALSE);
        }
        catch (IllegalArgumentException e)
        {
            sce.getServletContext().setAttribute("com.acme.AnnotationTest.sclFromSclRegoTest", Boolean.TRUE);
        }
        catch (Exception e)
        {
            sce.getServletContext().setAttribute("com.acme.AnnotationTest.sclFromSclRegoTest", Boolean.FALSE);
        }
        
        
        //Can't add an EventListener not part of the specified list for addListener()
        try
        {
            sce.getServletContext().addListener(new InvalidListener());
            sce.getServletContext().setAttribute("com.acme.AnnotationTest.invalidListenerRegoTest", Boolean.FALSE);
        }
        catch (IllegalArgumentException e)
        {
            sce.getServletContext().setAttribute("com.acme.AnnotationTest.invalidListenerRegoTest", Boolean.TRUE);
        }
        catch (Exception e)
        {
            sce.getServletContext().setAttribute("com.acme.AnnotationTest.invalidListenerRegoTest", Boolean.FALSE);
        } 
        
        //Programmatically add a listener and make sure its injected
        try
        {
            ValidListener l = sce.getServletContext().createListener(ValidListener.class);
            sce.getServletContext().setAttribute("com.acme.AnnotationTest.programListenerInjectTest", Boolean.valueOf(l != null && l.maxAmount != null));
        }   
        catch (Exception e)
        {
            sce.getServletContext().setAttribute("com.acme.AnnotationTest.programListenerInjectTest", Boolean.FALSE);
        }
    }

    public void contextDestroyed(ServletContextEvent sce)
    {
        // System.err.println("contextDestroyed "+sce);
    }

    public void attributeAdded(ServletContextAttributeEvent scab)
    {
        // System.err.println("attributeAdded "+scab);
    }

    public void attributeRemoved(ServletContextAttributeEvent scab)
    {
        // System.err.println("attributeRemoved "+scab);
    }

    public void attributeReplaced(ServletContextAttributeEvent scab)
    {
        // System.err.println("attributeReplaced "+scab);
    }

    public void requestDestroyed(ServletRequestEvent sre)
    {
        // System.err.println("requestDestroyed "+sre);
    }

    public void requestInitialized(ServletRequestEvent sre)
    {
        // System.err.println("requestInitialized "+sre);
    }

    public void attributeAdded(ServletRequestAttributeEvent srae)
    {
        // System.err.println("attributeAdded "+srae);
    }

    public void attributeRemoved(ServletRequestAttributeEvent srae)
    {
        // System.err.println("attributeRemoved "+srae);
    }

    public void attributeReplaced(ServletRequestAttributeEvent srae)
    {
        // System.err.println("attributeReplaced "+srae);
    }

    public void sessionCreated(HttpSessionEvent se)
    {
        // System.err.println("sessionCreated "+se);
    }

    public void sessionDestroyed(HttpSessionEvent se)
    {
        // System.err.println("sessionDestroyed "+se);
    }

    public void requestCompleted(ServletRequestEvent rre)
    {
        // TODO Auto-generated method stub
        
    }

    public void requestResumed(ServletRequestEvent rre)
    {
        // TODO Auto-generated method stub
        
    }

    public void requestSuspended(ServletRequestEvent rre)
    {
        // TODO Auto-generated method stub
        
    }

}
