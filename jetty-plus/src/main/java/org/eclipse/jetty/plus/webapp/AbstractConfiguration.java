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
import org.eclipse.jetty.webapp.Configuration;
import org.eclipse.jetty.webapp.WebAppContext;
import org.eclipse.jetty.webapp.WebXmlProcessor;
import org.eclipse.jetty.webapp.WebXmlProcessor.Descriptor;
import org.eclipse.jetty.xml.XmlParser;



/**
 * Configuration
 *
 *
 */
public abstract class AbstractConfiguration implements Configuration
{
    public abstract void bindEnvEntry (WebAppContext context, String name, Object value) throws Exception;
    
    public abstract void bindResourceRef (WebAppContext context, String name, Class type) throws Exception;
    
    public abstract void bindResourceEnvRef (WebAppContext context, String name, Class type) throws Exception;
    
    public abstract void bindUserTransaction (WebAppContext context) throws Exception;
    
    public abstract void bindMessageDestinationRef (WebAppContext context, String name, Class type)  throws Exception;
   
    
    public class PlusWebXmlProcessor
    {
        WebAppContext _context;
        
        public PlusWebXmlProcessor (WebAppContext context)
        {
            _context = context;
        }

        public void process (Descriptor d)
        throws Exception
        {
            if (d != null)
                process(d.getRoot());
        }
        
        public void process (XmlParser.Node root)
        throws Exception
        {
            if (root == null)
                return;
            
            
            Iterator iter = root.iterator();
            XmlParser.Node node = null;
            while (iter.hasNext())
            {
                try
                {
                    Object o = iter.next();
                    if (!(o instanceof XmlParser.Node)) continue;
                    node = (XmlParser.Node) o;
                    String name = node.getTag();
                    initWebXmlElement(name, node);
                }
                catch (ClassNotFoundException e)
                {
                    throw e;
                }
                catch (Exception e)
                {
                    Log.warn("Configuration problem at " + node, e);
                    throw new UnavailableException("Configuration problem");
                }
            } 
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
            bindEnvEntry(_context, name, value);
            
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
                typeClass = _context.loadClass(type);
            initInjection (node, jndiName, typeClass);
            
            bindResourceRef(_context, jndiName, typeClass);
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
                typeClass = _context.loadClass(type);
            initInjection (node, jndiName, typeClass);
            
            bindResourceEnvRef(_context, jndiName, typeClass);
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
                typeClass = _context.loadClass(type);
            initInjection(node, jndiName, typeClass);
            
            bindMessageDestinationRef(_context, jndiName, typeClass);
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
            
            LifeCycleCallbackCollection callbacks = (LifeCycleCallbackCollection)_context.getAttribute(LifeCycleCallbackCollection.LIFECYCLE_CALLBACK_COLLECTION);
            try
            {
                Class clazz = _context.loadClass(className);
                LifeCycleCallback callback = new PostConstructCallback();
                callback.setTarget(clazz, methodName);
                callbacks.add(callback);
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
            LifeCycleCallbackCollection callbacks = (LifeCycleCallbackCollection)_context.getAttribute(LifeCycleCallbackCollection.LIFECYCLE_CALLBACK_COLLECTION);
            try
            {
                Class clazz = _context.loadClass(className);
                LifeCycleCallback callback = new PreDestroyCallback();
                callback.setTarget(clazz, methodName);
                callbacks.add(callback);
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

                InjectionCollection injections = (InjectionCollection)_context.getAttribute(InjectionCollection.INJECTION_COLLECTION);
                // comments in the javaee_5.xsd file specify that the targetName is looked
                // for first as a java bean property, then if that fails, as a field
                try
                {
                    Class clazz = _context.loadClass(targetClassName);
                    Injection injection = new Injection();
                    injection.setJndiName(jndiName);
                    injection.setTarget(clazz, targetName, valueClass);
                     injections.add(injection);
                }
                catch (ClassNotFoundException e)
                {
                    Log.warn("Couldn't load injection target class "+targetClassName);
                }
            }
        }
    }
    
  
    
    public void preConfigure (WebAppContext context)
    throws Exception
    {
      //set up our special ServletHandler to remember injections and lifecycle callbacks
        ServletHandler servletHandler = new ServletHandler();
        SecurityHandler securityHandler = context.getSecurityHandler();
        org.eclipse.jetty.servlet.ServletHandler existingHandler = context.getServletHandler(); 
        servletHandler.setFilters(existingHandler.getFilters());
        servletHandler.setFilterMappings(existingHandler.getFilterMappings());    
        servletHandler.setServlets(existingHandler.getServlets());
        servletHandler.setServletMappings(existingHandler.getServletMappings());
        context.setServletHandler(servletHandler);
        securityHandler.setHandler(servletHandler);  
        
        LifeCycleCallbackCollection callbacks = new LifeCycleCallbackCollection();
        context.setAttribute(LifeCycleCallbackCollection.LIFECYCLE_CALLBACK_COLLECTION, callbacks);
        InjectionCollection injections = new InjectionCollection();
        context.setAttribute(InjectionCollection.INJECTION_COLLECTION, injections);
        RunAsCollection runAsCollection = new RunAsCollection();
        context.setAttribute(RunAsCollection.RUNAS_COLLECTION, runAsCollection);  
    }
   
    public void postConfigure(WebAppContext context) throws Exception
    {
        context.setAttribute(LifeCycleCallbackCollection.LIFECYCLE_CALLBACK_COLLECTION, null);
        context.setAttribute(InjectionCollection.INJECTION_COLLECTION, null);
        context.setAttribute(RunAsCollection.RUNAS_COLLECTION, null); 
    }

    public void configure (WebAppContext context)
    throws Exception
    {
        bindUserTransaction(context);
        
        WebXmlProcessor webXmlProcessor = (WebXmlProcessor)context.getAttribute(WebXmlProcessor.WEB_PROCESSOR); 
        if (webXmlProcessor == null)
           throw new IllegalStateException ("No processor for web xml");

        //TODO: When webdefaults.xml, web.xml, fragments and web-override.xml are merged into an effective web.xml this 
        //will change
        PlusWebXmlProcessor plusProcessor = new PlusWebXmlProcessor(context);
        plusProcessor.process(webXmlProcessor.getWebDefault());
        plusProcessor.process(webXmlProcessor.getWebXml());

        //Process plus-elements of each descriptor
        for (Descriptor frag: webXmlProcessor.getFragments())
        {
            plusProcessor.process(frag);
        }

        //process the override-web.xml descriptor
        plusProcessor.process(webXmlProcessor.getOverrideWeb());
        
        
        //configure injections and callbacks to be called by the FilterHolder and ServletHolder
        //when they lazily instantiate the Filter/Servlet.
        ((ServletHandler)context.getServletHandler()).setInjections((InjectionCollection)context.getAttribute(InjectionCollection.INJECTION_COLLECTION));
        ((ServletHandler)context.getServletHandler()).setCallbacks((LifeCycleCallbackCollection)context.getAttribute(LifeCycleCallbackCollection.LIFECYCLE_CALLBACK_COLLECTION));
        
        //do any injects on the listeners that were created and then
        //also callback any postConstruct lifecycle methods
        injectAndCallPostConstructCallbacks(context);
    }
    
    public void deconfigure (WebAppContext context)
    throws Exception
    {
        //call any preDestroy methods on the listeners
        callPreDestroyCallbacks(context);       
    }
     
    
    
    protected void injectAndCallPostConstructCallbacks(WebAppContext context)
    throws Exception
    {
        InjectionCollection injections = (InjectionCollection)context.getAttribute(InjectionCollection.INJECTION_COLLECTION);
        RunAsCollection runAsCollection = (RunAsCollection)context.getAttribute(RunAsCollection.RUNAS_COLLECTION);
        LifeCycleCallbackCollection callbacks = (LifeCycleCallbackCollection)context.getAttribute(LifeCycleCallbackCollection.LIFECYCLE_CALLBACK_COLLECTION);
        SecurityHandler securityHandler = context.getSecurityHandler();
        
        //look thru the servlets to apply any runAs annotations
        //NOTE: that any run-as in web.xml will already have been applied
        if (runAsCollection != null)
        {
            ServletHolder[] holders = context.getServletHandler().getServlets();
            for (int i=0;holders!=null && i<holders.length;i++)
            {
                runAsCollection.setRunAs(holders[i], securityHandler);
            }
        }

        EventListener[] listeners = context.getEventListeners();
        for (int i=0;listeners!=null && i<listeners.length;i++)
        {
            if (injections != null)
                injections.inject(listeners[i]);
            if (callbacks != null)
                callbacks.callPostConstructCallback(listeners[i]);
        }
    }
    
    
    protected void callPreDestroyCallbacks (WebAppContext context)
    throws Exception
    {   
        LifeCycleCallbackCollection callbacks = (LifeCycleCallbackCollection)context.getAttribute(LifeCycleCallbackCollection.LIFECYCLE_CALLBACK_COLLECTION);

        if (callbacks != null)
        {
            EventListener[] listeners = context.getEventListeners();
            for (int i=0; listeners!=null && i<listeners.length;i++)
            {
                callbacks.callPreDestroyCallback(listeners[i]);
            }
        }
    }
   
}
