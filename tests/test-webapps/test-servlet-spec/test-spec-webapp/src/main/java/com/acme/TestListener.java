//========================================================================
//$Id: TestListener.java 3363 2008-07-22 13:40:59Z janb $
//Copyright 2004-2005 Mort Bay Consulting Pty. Ltd.
//------------------------------------------------------------------------
//Licensed under the Apache License, Version 2.0 (the "License");
//you may not use this file except in compliance with the License.
//You may obtain a copy of the License at 
//http://www.apache.org/licenses/LICENSE-2.0
//Unless required by applicable law or agreed to in writing, software
//distributed under the License is distributed on an "AS IS" BASIS,
//WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
//See the License for the specific language governing permissions and
//limitations under the License.
//========================================================================

package com.acme;
import javax.annotation.Resource;
import javax.servlet.annotation.WebListener;
import javax.servlet.ServletContextAttributeEvent;
import javax.servlet.ServletContextAttributeListener;
import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;
import javax.servlet.ServletRequestAttributeEvent;
import javax.servlet.ServletRequestAttributeListener;
import javax.servlet.ServletRequestEvent;
import javax.servlet.ServletRequestListener;
import javax.servlet.http.HttpSessionActivationListener;
import javax.servlet.http.HttpSessionAttributeListener;
import javax.servlet.http.HttpSessionBindingEvent;
import javax.servlet.http.HttpSessionEvent;
import javax.servlet.http.HttpSessionListener;
import com.acme.Foo;


@Foo(1)
@WebListener
public class TestListener implements HttpSessionListener,  HttpSessionAttributeListener, HttpSessionActivationListener, ServletContextListener, ServletContextAttributeListener, ServletRequestListener, ServletRequestAttributeListener
{

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
        //System.err.println("contextInitialized, maxAmount injected as "+maxAmount);
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
