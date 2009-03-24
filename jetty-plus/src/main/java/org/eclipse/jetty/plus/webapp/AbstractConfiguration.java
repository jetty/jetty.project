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

package org.eclipse.jetty.plus.webapp;


import java.util.EventListener;
import java.util.Iterator;

import javax.servlet.UnavailableException;

import org.eclipse.jetty.plus.annotation.Injection;
import org.eclipse.jetty.plus.annotation.InjectionCollection;
import org.eclipse.jetty.plus.annotation.LifeCycleCallback;
import org.eclipse.jetty.plus.annotation.LifeCycleCallbackCollection;
import org.eclipse.jetty.plus.annotation.PostConstructCallback;
import org.eclipse.jetty.plus.annotation.PreDestroyCallback;
import org.eclipse.jetty.plus.annotation.RunAsCollection;
import org.eclipse.jetty.plus.servlet.ServletHandler;
import org.eclipse.jetty.security.SecurityHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.util.TypeUtil;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.webapp.WebAppContext;
import org.eclipse.jetty.webapp.WebXmlConfiguration;
import org.eclipse.jetty.xml.XmlParser;



/**
 * Configuration
 *
 *
 */
public abstract class AbstractConfiguration extends WebXmlConfiguration
{
    protected LifeCycleCallbackCollection _callbacks = new LifeCycleCallbackCollection();
    protected InjectionCollection _injections = new InjectionCollection();
    protected RunAsCollection _runAsCollection = new RunAsCollection();
    protected SecurityHandler _securityHandler;
    
    public abstract void bindEnvEntry (String name, Object value) throws Exception;
    
    public abstract void bindResourceRef (String name, Class type) throws Exception;
    
    public abstract void bindResourceEnvRef (String name, Class type) throws Exception;
    
    public abstract void bindUserTransaction () throws Exception;
    
    public abstract void bindMessageDestinationRef (String name, Class type)  throws Exception;
    
    
    /**
     * @throws ClassNotFoundException
     */
    public AbstractConfiguration() throws ClassNotFoundException
    {
        super();
    }

    
    public void setWebAppContext (WebAppContext context)
    {
        super.setWebAppContext(context);
        
        //set up our special ServletHandler to remember injections and lifecycle callbacks
        ServletHandler servletHandler = new ServletHandler();
        _securityHandler = getWebAppContext().getSecurityHandler();
        org.eclipse.jetty.servlet.ServletHandler existingHandler = getWebAppContext().getServletHandler();       
        servletHandler.setFilterMappings(existingHandler.getFilterMappings());
        servletHandler.setFilters(existingHandler.getFilters());
        servletHandler.setServlets(existingHandler.getServlets());
        servletHandler.setServletMappings(existingHandler.getServletMappings());
        getWebAppContext().setServletHandler(servletHandler);
        _securityHandler.setHandler(servletHandler);       
    }
    
    public void configureDefaults ()
    throws Exception
    {
        super.configureDefaults();
    }
   
    public void configureWebApp ()
    throws Exception
    {
        super.configureWebApp();
        bindUserTransaction();
    }
    
    public void deconfigureWebApp()
    throws Exception
    {
        //call any preDestroy methods on the listeners
        callPreDestroyCallbacks();
        
        super.deconfigureWebApp();
    }
    
    public void configure(String webXml)
    throws Exception
    {
        //parse web.xml
        super.configure(webXml);
        
        //parse classes for annotations, if necessary
        if (!_metaDataComplete)
        {
            if (Log.isDebugEnabled()) Log.debug("Processing annotations");
            parseAnnotations();
        }
        //do any injects on the listeners that were created and then
        //also callback any postConstruct lifecycle methods
        injectAndCallPostConstructCallbacks();
    }

    
  
    
    
    protected void initialize(XmlParser.Node config) 
    throws ClassNotFoundException,UnavailableException
    {
        super.initialize(config);
        
        //configure injections and callbacks to be called by the FilterHolder and ServletHolder
        //when they lazily instantiate the Filter/Servlet.
        ((ServletHandler)getWebAppContext().getServletHandler()).setInjections(_injections);
        ((ServletHandler)getWebAppContext().getServletHandler()).setCallbacks(_callbacks);
    }
    
    
    protected void initWebXmlElement(String element,XmlParser.Node node) throws Exception
    {
        if ("env-entry".equals(element))
        {
            initEnvEntry (node);
        }
        else if ("resource-ref".equals(element))
        {
            //resource-ref entries are ONLY for connection factories
            //the resource-ref says how the app will reference the jndi lookup relative
            //to java:comp/env, but it is up to the deployer to map this reference to
            //a real resource in the environment. At the moment, we insist that the
            //jetty.xml file name of the resource has to be exactly the same as the
            //name in web.xml deployment descriptor, but it shouldn't have to be
            initResourceRef(node);
        }
        else if ("resource-env-ref".equals(element))
        {
            //resource-env-ref elements are a non-connection factory type of resource
            //the app looks them up relative to java:comp/env
            //again, need a way for deployer to link up app naming to real naming.
            //Again, we insist now that the name of the resource in jetty.xml is
            //the same as web.xml
            initResourceEnvRef(node);      
        }
        else if ("message-destination-ref".equals(element))
        {
            initMessageDestinationRef(node);
        }
        else if ("post-construct".equals(element))
        {
            //post-construct is the name of a class and method to call after all
            //resources have been setup but before the class is put into use
            initPostConstruct(node);
        }
        else if ("pre-destroy".equals(element))
        {
            //pre-destroy is the name of a class and method to call just as
            //the instance is being destroyed
            initPreDestroy(node);
        }
        else
        {
            super.initWebXmlElement(element, node);
        }
 
    }
    
    /**
     * JavaEE 5.4.1.3 
     * 
     * 
     * @param node
     * @throws Exception
     */
    protected void initEnvEntry (XmlParser.Node node)
    throws Exception
    {
        String name=node.getString("env-entry-name",false,true);
        String type = node.getString("env-entry-type",false,true);
        String valueStr = node.getString("env-entry-value",false,true);
        
        //if there's no value there's no point in making a jndi entry
        //nor processing injection entries
        if (valueStr==null || valueStr.equals(""))
        {
            Log.warn("No value for env-entry-name "+name);
            return;
        }
      
        //the javaee_5.xsd says that the env-entry-type is optional
        //if there is an <injection> element, because you can get
        //type from the element, but what to do if there is more
        //than one <injection> element, do you just pick the type
        //of the first one?
        
        //check for <injection> elements
        initInjection (node, name, TypeUtil.fromName(type));
       
        //bind the entry into jndi
        Object value = TypeUtil.valueOf(type,valueStr);
        bindEnvEntry(name, value);
        
    }
    
    
    /**
     * Common Annotations Spec section 2.3:
     *  resource-ref is for:
     *    - javax.sql.DataSource
     *    - javax.jms.ConnectionFactory
     *    - javax.jms.QueueConnectionFactory
     *    - javax.jms.TopicConnectionFactory
     *    - javax.mail.Session
     *    - java.net.URL
     *    - javax.resource.cci.ConnectionFactory
     *    - org.omg.CORBA_2_3.ORB
     *    - any other connection factory defined by a resource adapter
     * @param node
     * @throws Exception
     */
    protected void initResourceRef (XmlParser.Node node)
    throws Exception
    {
        String jndiName = node.getString("res-ref-name",false,true);
        String type = node.getString("res-type", false, true);
        String auth = node.getString("res-auth", false, true);
        String shared = node.getString("res-sharing-scope", false, true);

        //check for <injection> elements
        Class typeClass = TypeUtil.fromName(type);
        if (typeClass==null)
            typeClass = getWebAppContext().loadClass(type);
        initInjection (node, jndiName, typeClass);
        
        bindResourceRef(jndiName, typeClass);
    }
    
    
    /**
     * Common Annotations Spec section 2.3:
     *   resource-env-ref is for:
     *     - javax.transaction.UserTransaction
     *     - javax.resource.cci.InteractionSpec
     *     - anything else that is not a connection factory
     * @param node
     * @throws Exception
     */
    protected void initResourceEnvRef (XmlParser.Node node)
    throws Exception
    {
        String jndiName = node.getString("resource-env-ref-name",false,true);
        String type = node.getString("resource-env-ref-type", false, true);

        //check for <injection> elements
        
        //JavaEE Spec sec 5.7.1.3 says the resource-env-ref-type
        //is mandatory, but the schema says it is optional!
        Class typeClass = TypeUtil.fromName(type);
        if (typeClass==null)
            typeClass = getWebAppContext().loadClass(type);
        initInjection (node, jndiName, typeClass);
        
        bindResourceEnvRef(jndiName, typeClass);
    }
    
    
    /**
     * Common Annotations Spec section 2.3:
     *   message-destination-ref is for:
     *     - javax.jms.Queue
     *     - javax.jms.Topic
     * @param node
     * @throws Exception
     */
    protected void initMessageDestinationRef (XmlParser.Node node)
    throws Exception
    {
        String jndiName = node.getString("message-destination-ref-name",false,true);
        String type = node.getString("message-destination-type",false,true);
        String usage = node.getString("message-destination-usage",false,true);
        
        Class typeClass = TypeUtil.fromName(type);
        if (typeClass==null)
            typeClass = getWebAppContext().loadClass(type);
        initInjection(node, jndiName, typeClass);
        
        bindMessageDestinationRef(jndiName, typeClass);
    }
    
    
    
    /**
     * Process &lt;post-construct&gt;
     * @param node
     */
    protected void initPostConstruct(XmlParser.Node node)
    {
        String className = node.getString("lifecycle-callback-class", false, true);
        String methodName = node.getString("lifecycle-callback-method", false, true);
        
        if (className==null || className.equals(""))
        {
            Log.warn("No lifecycle-callback-class specified");
            return;
        }
        if (methodName==null || methodName.equals(""))
        {
            Log.warn("No lifecycle-callback-method specified for class "+className);
            return;
        }
        
        try
        {
            Class clazz = getWebAppContext().loadClass(className);
            LifeCycleCallback callback = new PostConstructCallback();
            callback.setTarget(clazz, methodName);
            _callbacks.add(callback);
        }
        catch (ClassNotFoundException e)
        {
            Log.warn("Couldn't load post-construct target class "+className);
        }
    }
    
    
    /**
     * Process &lt;pre-destroy&gt;
     * @param node
     */
    protected void initPreDestroy(XmlParser.Node node)
    {
        String className = node.getString("lifecycle-callback-class", false, true);
        String methodName = node.getString("lifecycle-callback-method", false, true);
        if (className==null || className.equals(""))
        {
            Log.warn("No lifecycle-callback-class specified for pre-destroy");
            return;
        }
        if (methodName==null || methodName.equals(""))
        {
            Log.warn("No lifecycle-callback-method specified for pre-destroy class "+className);
            return;
        } 
        
        try
        {
            Class clazz = getWebAppContext().loadClass(className);
            LifeCycleCallback callback = new PreDestroyCallback();
            callback.setTarget(clazz, methodName);
            _callbacks.add(callback);
        }
        catch (ClassNotFoundException e)
        {
            Log.warn("Couldn't load pre-destory target class "+className);
        }
    }
    
    
    /**
     * Iterate over the &lt;injection-target&gt; entries for a node
     * 
     * @param node
     * @param jndiName
     * @param valueClass
     * @return the type of the injectable
     */
    protected void initInjection (XmlParser.Node node, String jndiName, Class valueClass)
    {
        Iterator  itor = node.iterator("injection-target");
        
        while(itor.hasNext())
        {
            XmlParser.Node injectionNode = (XmlParser.Node)itor.next(); 
            String targetClassName = injectionNode.getString("injection-target-class", false, true);
            String targetName = injectionNode.getString("injection-target-name", false, true);
            if ((targetClassName==null) || targetClassName.equals(""))
            {
                Log.warn("No classname found in injection-target");
                continue;
            }
            if ((targetName==null) || targetName.equals(""))
            {
                Log.warn("No field or method name in injection-target");
                continue;
            }

            // comments in the javaee_5.xsd file specify that the targetName is looked
            // for first as a java bean property, then if that fails, as a field
            try
            {
                Class clazz = getWebAppContext().loadClass(targetClassName);
                Injection injection = new Injection();
                injection.setTargetClass(clazz);
                injection.setJndiName(jndiName);
                injection.setTarget(clazz, targetName, valueClass);
                 _injections.add(injection);
            }
            catch (ClassNotFoundException e)
            {
                Log.warn("Couldn't load injection target class "+targetClassName);
            }
        }
    }
    
    
    /**
     * Parse all classes that are mentioned in web.xml (servlets, filters, listeners)
     * for annotations.
     * 
     * 
     * 
     * @throws Exception
     */
    protected abstract void parseAnnotations () throws Exception;
   
    
    
    protected void injectAndCallPostConstructCallbacks()
    throws Exception
    {
        //look thru the servlets to apply any runAs annotations
        //NOTE: that any run-as in web.xml will already have been applied
        ServletHolder[] holders = getWebAppContext().getServletHandler().getServlets();
        for (int i=0;holders!=null && i<holders.length;i++)
        {
            _runAsCollection.setRunAs(holders[i], _securityHandler);
        }

        EventListener[] listeners = getWebAppContext().getEventListeners();
        for (int i=0;listeners!=null && i<listeners.length;i++)
        {
            _injections.inject(listeners[i]);
            _callbacks.callPostConstructCallback(listeners[i]);
        }
    }
    
    
    protected void callPreDestroyCallbacks ()
    throws Exception
    {
        EventListener[] listeners = getWebAppContext().getEventListeners();
        for (int i=0; listeners!=null && i<listeners.length;i++)
        {
            _callbacks.callPreDestroyCallback(listeners[i]);
        }
    }
   
}
