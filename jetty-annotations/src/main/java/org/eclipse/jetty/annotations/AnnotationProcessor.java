// ========================================================================
// Copyright (c) 2008-2009 Mort Bay Consulting Pty. Ltd.
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

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.annotation.Resource;
import javax.annotation.Resources;
import javax.annotation.security.RunAs;
import javax.naming.InitialContext;
import javax.naming.NameNotFoundException;
import javax.naming.NamingException;
import javax.servlet.DispatcherType;
import javax.servlet.http.annotation.InitParam;
import javax.servlet.http.annotation.jaxrs.DELETE;
import javax.servlet.http.annotation.jaxrs.GET;
import javax.servlet.http.annotation.jaxrs.HEAD;
import javax.servlet.http.annotation.jaxrs.POST;
import javax.servlet.http.annotation.jaxrs.PUT;

import org.eclipse.jetty.plus.annotation.Injection;
import org.eclipse.jetty.plus.annotation.InjectionCollection;
import org.eclipse.jetty.plus.annotation.LifeCycleCallbackCollection;
import org.eclipse.jetty.plus.annotation.PojoContextListener;
import org.eclipse.jetty.plus.annotation.PojoFilter;
import org.eclipse.jetty.plus.annotation.PojoServlet;
import org.eclipse.jetty.plus.annotation.PostConstructCallback;
import org.eclipse.jetty.plus.annotation.PreDestroyCallback;
import org.eclipse.jetty.plus.annotation.RunAsCollection;
import org.eclipse.jetty.servlet.FilterHolder;
import org.eclipse.jetty.servlet.FilterMapping;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.servlet.ServletMapping;
import org.eclipse.jetty.util.IntrospectionUtil;
import org.eclipse.jetty.util.LazyList;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.webapp.WebAppContext;



/**
 * AnnotationProcessor
 *
 * Act on the annotations discovered in the webapp.
 */
public class AnnotationProcessor
{
    AnnotationFinder _finder;
    ClassLoader _loader;
    RunAsCollection _runAs;
    InjectionCollection _injections;
    LifeCycleCallbackCollection _callbacks;
    List _servlets;
    List _filters;
    List _listeners;
    List _servletMappings;
    List _filterMappings;
    Map _pojoInstances = new HashMap();
    WebAppContext _webApp;
    
    private static Class[] __envEntryTypes = 
        new Class[] {String.class, Character.class, Integer.class, Boolean.class, Double.class, Byte.class, Short.class, Long.class, Float.class};
   
    public AnnotationProcessor(WebAppContext webApp, AnnotationFinder finder, RunAsCollection runAs, InjectionCollection injections, LifeCycleCallbackCollection callbacks,
            List servlets, List filters, List listeners, List servletMappings, List filterMappings)
    {
        _webApp=webApp;
        _finder=finder;
        _runAs=runAs;
        _injections=injections;
        _callbacks=callbacks;
        _servlets=servlets;
        _filters=filters;
        _listeners=listeners;
        _servletMappings=servletMappings;
        _filterMappings=filterMappings;
    }
    
    
    public void process ()
    throws Exception
    { 
        processServlets();
        processFilters();
        processListeners();
        processRunAsAnnotations();
        processLifeCycleCallbackAnnotations();
        processResourcesAnnotations();
        processResourceAnnotations();
    }
    
    public void processServlets ()
    throws Exception
    {
        //@Servlet(urlMappings=String[], description=String, icon=String, loadOnStartup=int, name=String, initParams=InitParams[])
        for (Class clazz:_finder.getClassesForAnnotation(javax.servlet.http.annotation.Servlet.class))
        {
            javax.servlet.http.annotation.Servlet annotation = (javax.servlet.http.annotation.Servlet)clazz.getAnnotation(javax.servlet.http.annotation.Servlet.class);
            PojoServlet servlet = new PojoServlet(getPojoInstanceFor(clazz));
            
            List<Method> methods = _finder.getMethodsForAnnotation(GET.class);
            if (methods.size() > 1)
                throw new IllegalStateException ("More than one GET annotation on "+clazz.getName());           
            else if (methods.size() == 1)
                servlet.setGetMethodName(methods.get(0).getName());
           
            methods = _finder.getMethodsForAnnotation(POST.class);
            if (methods.size() > 1)
                throw new IllegalStateException ("More than one POST annotation on "+clazz.getName());
            else if (methods.size() == 1)
                servlet.setPostMethodName(methods.get(0).getName());
            
            methods = _finder.getMethodsForAnnotation(PUT.class);
            if (methods.size() > 1)
                throw new IllegalStateException ("More than one PUT annotation on "+clazz.getName());
            else if (methods.size() == 1)
                servlet.setPutMethodName(methods.get(0).getName());
            
            methods = _finder.getMethodsForAnnotation(DELETE.class);
            if (methods.size() > 1)
                throw new IllegalStateException ("More than one DELETE annotation on "+clazz.getName());
            else if (methods.size() == 1)
                servlet.setDeleteMethodName(methods.get(0).getName());
            
            methods = _finder.getMethodsForAnnotation(HEAD.class);
            if (methods.size() > 1)
                throw new IllegalStateException ("More than one HEAD annotation on "+clazz.getName());
            else if (methods.size() == 1)
                servlet.setHeadMethodName(methods.get(0).getName());
            
            ServletHolder holder = new ServletHolder(servlet);
            holder.setName((annotation.name().equals("")?clazz.getName():annotation.name()));
            holder.setInitOrder(annotation.loadOnStartup());
            LazyList.add(_servlets, holder);
            
            for (InitParam ip:annotation.initParams())
            {
                holder.setInitParameter(ip.name(), ip.value());
            }
            
            if (annotation.urlMappings().length > 0)
            {
                ArrayList paths = new ArrayList();
                ServletMapping mapping = new ServletMapping();
                mapping.setServletName(holder.getName());
                for (String s:annotation.urlMappings())
                {    
                    paths.add(normalizePattern(s)); 
                }
                mapping.setPathSpecs((String[])paths.toArray(new String[paths.size()]));
                LazyList.add(_servletMappings,mapping);
            }
        } 
    }
    
    public void processFilters ()
    throws Exception
    {
        //@ServletFilter(description=String, filterName=String, displayName=String, icon=String,initParams=InitParam[], filterMapping=FilterMapping)
        for (Class clazz:_finder.getClassesForAnnotation(javax.servlet.http.annotation.ServletFilter.class))
        {
            javax.servlet.http.annotation.ServletFilter annotation = (javax.servlet.http.annotation.ServletFilter)clazz.getAnnotation(javax.servlet.http.annotation.ServletFilter.class);
            PojoFilter filter = new PojoFilter(getPojoInstanceFor(clazz));

            FilterHolder holder = new FilterHolder(filter);
            holder.setName((annotation.filterName().equals("")?clazz.getName():annotation.filterName()));
            holder.setDisplayName(annotation.displayName());
            LazyList.add(_filters, holder);
            
            for (InitParam ip:annotation.initParams())
            {
                holder.setInitParameter(ip.name(), ip.value());
            }
            
            if (annotation.filterMapping() != null)
            {
                FilterMapping mapping = new FilterMapping();
                mapping.setFilterName(holder.getName());
                ArrayList paths = new ArrayList();
                for (String s:annotation.filterMapping().urlPattern())
                {
                    paths.add(normalizePattern(s));
                }
                mapping.setPathSpecs((String[])paths.toArray(new String[paths.size()]));
                ArrayList names = new ArrayList();
                for (String s:annotation.filterMapping().servletNames())
                {
                    names.add(s);
                }
                mapping.setServletNames((String[])names.toArray(new String[names.size()]));
                
                int dispatcher=FilterMapping.DEFAULT;                
                for (DispatcherType d:annotation.filterMapping().dispatcherTypes())
                {
                   dispatcher = dispatcher|FilterMapping.dispatch(d);            
                }
                mapping.setDispatches(dispatcher);
                LazyList.add(_filterMappings,mapping);
            }
        }        
    }
    

    
    public void processListeners ()
    throws Exception
    {
        //@ServletContextListener(description=String)
        for (Class clazz:_finder.getClassesForAnnotation(javax.servlet.http.annotation.ServletContextListener.class))
        { 
            PojoContextListener listener = new PojoContextListener(getPojoInstanceFor(clazz));
            LazyList.add(_listeners, listener);
        }
    }
    
    
    public List getServlets ()
    {
        return _servlets;
    }
    
    public List getServletMappings ()
    {
        return _servletMappings;
    }
    
    public List getFilters ()
    {
        return _filters;
    }
    
    public List getFilterMappings ()
    {
        return _filterMappings;
    }
    
    public List getListeners()
    {
        return _listeners;
    }
   
    
    public void processRunAsAnnotations ()
    throws Exception
    {
        for (Class clazz:_finder.getClassesForAnnotation(RunAs.class))
        {
            if (!javax.servlet.Servlet.class.isAssignableFrom(clazz) && !(_pojoInstances.containsKey(clazz)))
            {
                Log.debug("Ignoring runAs notation on on-servlet class "+clazz.getName());
                continue;
            }
            RunAs runAs = (RunAs)clazz.getAnnotation(RunAs.class);
            if (runAs != null)
            {
                String role = runAs.value();
                if (role != null)
                {
                    org.eclipse.jetty.plus.annotation.RunAs ra = new org.eclipse.jetty.plus.annotation.RunAs();
                    ra.setTargetClass(clazz);
                    ra.setRoleName(role);
                    _runAs.add(ra);
                }
            }
        } 
    }
    
    
    public void processLifeCycleCallbackAnnotations()
    throws Exception
    {
        processPostConstructAnnotations();
        processPreDestroyAnnotations();
    }

    private void processPostConstructAnnotations ()
    throws Exception
    {
        //      TODO: check that the same class does not have more than one
        for (Method m:_finder.getMethodsForAnnotation(PostConstruct.class))
        {
            if (!isServletType(m.getDeclaringClass()))
            {
                Log.debug("Ignoring "+m.getName()+" as non-servlet type");
                continue;
            }
            if (m.getParameterTypes().length != 0)
                throw new IllegalStateException(m+" has parameters");
            if (m.getReturnType() != Void.TYPE)
                throw new IllegalStateException(m+" is not void");
            if (m.getExceptionTypes().length != 0)
                throw new IllegalStateException(m+" throws checked exceptions");
            if (Modifier.isStatic(m.getModifiers()))
                throw new IllegalStateException(m+" is static");

            PostConstructCallback callback = new PostConstructCallback();
            callback.setTargetClass(m.getDeclaringClass());
            callback.setTarget(m);
            _callbacks.add(callback);
        }
    }

    public void processPreDestroyAnnotations ()
    throws Exception
    {
        //TODO: check that the same class does not have more than one

        for (Method m: _finder.getMethodsForAnnotation(PreDestroy.class))
        {
            if (!isServletType(m.getDeclaringClass()))
            {
                Log.debug("Ignoring "+m.getName()+" as non-servlet type");
                continue;
            }
            if (m.getParameterTypes().length != 0)
                throw new IllegalStateException(m+" has parameters");
            if (m.getReturnType() != Void.TYPE)
                throw new IllegalStateException(m+" is not void");
            if (m.getExceptionTypes().length != 0)
                throw new IllegalStateException(m+" throws checked exceptions");
            if (Modifier.isStatic(m.getModifiers()))
                throw new IllegalStateException(m+" is static");
           
            PreDestroyCallback callback = new PreDestroyCallback(); 
            callback.setTargetClass(m.getDeclaringClass());
            callback.setTarget(m);
            _callbacks.add(callback);
        }
    }
    
    
    /**
     * Process @Resources annotation on classes
     */
    public void processResourcesAnnotations ()
    throws Exception
    {
        List<Class<?>> classes = _finder.getClassesForAnnotation(Resources.class);
        for (Class<?> clazz:classes)
        {
            if (!isServletType(clazz))
            {
                Log.debug("Ignoring @Resources annotation on on-servlet type class "+clazz.getName());
                continue;
            }
            //Handle Resources annotation - add namespace entries
            Resources resources = (Resources)clazz.getAnnotation(Resources.class);
            if (resources == null)
                continue;

            Resource[] resArray = resources.value();
            if (resArray==null||resArray.length==0)
                continue;

            for (int j=0;j<resArray.length;j++)
            {
                String name = resArray[j].name();
                String mappedName = resArray[j].mappedName();
                Resource.AuthenticationType auth = resArray[j].authenticationType();
                Class type = resArray[j].type();
                boolean shareable = resArray[j].shareable();

                if (name==null || name.trim().equals(""))
                    throw new IllegalStateException ("Class level Resource annotations must contain a name (Common Annotations Spec Section 2.3)");
                try
                {
                    //TODO don't ignore the shareable, auth etc etc

                       if (!org.eclipse.jetty.plus.jndi.NamingEntryUtil.bindToENC(_webApp, name, mappedName))
                           if (!org.eclipse.jetty.plus.jndi.NamingEntryUtil.bindToENC(_webApp.getServer(), name, mappedName))
                               throw new IllegalStateException("No resource bound at "+(mappedName==null?name:mappedName));
                }
                catch (NamingException e)
                {
                    throw new IllegalStateException(e);
                }
            }
        }
    }
    
    
    public void processResourceAnnotations ()
    throws Exception
    {
        processClassResourceAnnotations();
        processMethodResourceAnnotations();
        processFieldResourceAnnotations();
    }
    
    /**
     *  Class level Resource annotations declare a name in the
     *  environment that will be looked up at runtime. They do
     *  not specify an injection.
     */
    public void processClassResourceAnnotations ()
    throws Exception
    {
        List<Class<?>> classes = _finder.getClassesForAnnotation(Resource.class);
        for (Class<?> clazz:classes)
        {
            if (!isServletType(clazz))
            {
                Log.debug("Ignoring @Resource annotation on on-servlet type class "+clazz.getName());
                continue;
            }
            //Handle Resource annotation - add namespace entries
            Resource resource = (Resource)clazz.getAnnotation(Resource.class);
            if (resource != null)
            {
               String name = resource.name();
               String mappedName = resource.mappedName();
               Resource.AuthenticationType auth = resource.authenticationType();
               Class type = resource.type();
               boolean shareable = resource.shareable();
               
               if (name==null || name.trim().equals(""))
                   throw new IllegalStateException ("Class level Resource annotations must contain a name (Common Annotations Spec Section 2.3)");
               
               try
               {
                   //TODO don't ignore the shareable, auth etc etc
                   if (!org.eclipse.jetty.plus.jndi.NamingEntryUtil.bindToENC(_webApp, name,mappedName))
                       if (!org.eclipse.jetty.plus.jndi.NamingEntryUtil.bindToENC(_webApp.getServer(), name,mappedName))
                           throw new IllegalStateException("No resource at "+(mappedName==null?name:mappedName));
               }
               catch (NamingException e)
               {
                   throw new IllegalStateException(e);
               }
            }
        }
    }
    
    /**
     * Process a Resource annotation on the Methods.
     * 
     * This will generate a JNDI entry, and an Injection to be
     * processed when an instance of the class is created.
     * @param injections
     */
    public void processMethodResourceAnnotations ()
    throws Exception
    {
        //Get all methods that have a Resource annotation
        List<Method> methods = _finder.getMethodsForAnnotation(javax.annotation.Resource.class);

        for (Method m: methods)
        {
            if (!isServletType(m.getDeclaringClass()))
            {
                Log.debug("Ignoring @Resource annotation on on-servlet type method "+m.getName());
                continue;
            }
            /*
             * Commons Annotations Spec 2.3
             * " The Resource annotation is used to declare a reference to a resource.
             *   It can be specified on a class, methods or on fields. When the 
             *   annotation is applied on a field or method, the container will 
             *   inject an instance of the requested resource into the application 
             *   when the application is initialized... Even though this annotation 
             *   is not marked Inherited, if used all superclasses MUST be examined 
             *   to discover all uses of this annotation. All such annotation instances 
             *   specify resources that are needed by the application. Note that this 
             *   annotation may appear on private fields and methods of the superclasses. 
             *   Injection of the declared resources needs to happen in these cases as 
             *   well, even if a method with such an annotation is overridden by a subclass."
             *  
             *  Which IMHO, put more succinctly means "If you find a @Resource on any method
             *  or field, inject it!".
             */
            Resource resource = (Resource)m.getAnnotation(Resource.class);
            if (resource == null)
                continue;

            //JavaEE Spec 5.2.3: Method cannot be static
            if (Modifier.isStatic(m.getModifiers()))
                throw new IllegalStateException(m+" cannot be static");


            // Check it is a valid javabean 
            if (!IntrospectionUtil.isJavaBeanCompliantSetter(m))
                throw new IllegalStateException(m+" is not a java bean compliant setter method");

            //default name is the javabean property name
            String name = m.getName().substring(3);
            name = name.substring(0,1).toLowerCase()+name.substring(1);
            name = m.getDeclaringClass().getCanonicalName()+"/"+name;
            //allow default name to be overridden
            name = (resource.name()!=null && !resource.name().trim().equals("")? resource.name(): name);
            //get the mappedName if there is one
            String mappedName = (resource.mappedName()!=null && !resource.mappedName().trim().equals("")?resource.mappedName():null);

            Class type = m.getParameterTypes()[0];

            //get other parts that can be specified in @Resource
            Resource.AuthenticationType auth = resource.authenticationType();
            boolean shareable = resource.shareable();

            //if @Resource specifies a type, check it is compatible with setter param
            if ((resource.type() != null) 
                    && 
                    !resource.type().equals(Object.class)
                    &&
                    (!IntrospectionUtil.isTypeCompatible(type, resource.type(), false)))
                throw new IllegalStateException("@Resource incompatible type="+resource.type()+ " with method param="+type+ " for "+m);

            //check if an injection has already been setup for this target by web.xml
            Injection webXmlInjection = _injections.getInjection(m.getDeclaringClass(), m);
            if (webXmlInjection == null)
            {
                try
                {
                    //try binding name to environment
                    //try the webapp's environment first
                    boolean bound = org.eclipse.jetty.plus.jndi.NamingEntryUtil.bindToENC(_webApp, name, mappedName);
                    
                    //try the server's environment
                    if (!bound)
                        bound = org.eclipse.jetty.plus.jndi.NamingEntryUtil.bindToENC(_webApp.getServer(), name, mappedName);
                    
                    //try the jvm's environment
                    if (!bound)
                        bound = org.eclipse.jetty.plus.jndi.NamingEntryUtil.bindToENC(null, name, mappedName);
                    
                    //TODO if it is an env-entry from web.xml it can be injected, in which case there will be no
                    //NamingEntry, just a value bound in java:comp/env
                    if (!bound)
                    {
                        try
                        {
                            InitialContext ic = new InitialContext();
                            String nameInEnvironment = (mappedName!=null?mappedName:name);
                            ic.lookup("java:comp/env/"+nameInEnvironment);                               
                            bound = true;
                        }
                        catch (NameNotFoundException e)
                        {
                            bound = false;
                        }
                    }
                    
                    if (bound)
                    {
                        Log.debug("Bound "+(mappedName==null?name:mappedName) + " as "+ name);
                        //   Make the Injection for it
                        Injection injection = new Injection();
                        injection.setTargetClass(m.getDeclaringClass());
                        injection.setJndiName(name);
                        injection.setMappingName(mappedName);
                        injection.setTarget(m);
                        _injections.add(injection);
                    } 
                    else if (!isEnvEntryType(type))
                    {

                        //if this is an env-entry type resource and there is no value bound for it, it isn't
                        //an error, it just means that perhaps the code will use a default value instead
                        // JavaEE Spec. sec 5.4.1.3   
                        throw new IllegalStateException("No resource at "+(mappedName==null?name:mappedName));
                    }
                }
                catch (NamingException e)
                {  
                    //if this is an env-entry type resource and there is no value bound for it, it isn't
                    //an error, it just means that perhaps the code will use a default value instead
                    // JavaEE Spec. sec 5.4.1.3
                    if (!isEnvEntryType(type))
                        throw new IllegalStateException(e);
                }
            }
            else
            {
                //if an injection is already set up for this name, then the types must be compatible
                //JavaEE spec sec 5.2.4

                Object value = webXmlInjection.lookupInjectedValue();
                if (!IntrospectionUtil.isTypeCompatible(type, value.getClass(), false))
                    throw new IllegalStateException("Type of field="+type+" is not compatible with Resource type="+value.getClass());
            }
        }
    }

    /**
     * Process @Resource annotation for a Field. These will both set up a
     * JNDI entry and generate an Injection. Or they can be the equivalent
     * of env-entries with default values
     * 
     * @param injections
     */
    public void processFieldResourceAnnotations ()
    throws Exception
    {
        //Get all fields that have a Resource annotation
        List<Field> fields = _finder.getFieldsForAnnotation(Resource.class);
        for (Field f: fields)
        {
            if (!isServletType(f.getDeclaringClass()))
            {
                Log.debug("Ignoring @Resource annotation on on-servlet type field "+f.getName());
                continue;
            }
            Resource resource = (Resource)f.getAnnotation(Resource.class);
            if (resource == null)
                continue;

            //JavaEE Spec 5.2.3: Field cannot be static
            if (Modifier.isStatic(f.getModifiers()))
                throw new IllegalStateException(f+" cannot be static");

            //JavaEE Spec 5.2.3: Field cannot be final
            if (Modifier.isFinal(f.getModifiers()))
                throw new IllegalStateException(f+" cannot be final");

            //work out default name
            String name = f.getDeclaringClass().getCanonicalName()+"/"+f.getName();
            //allow @Resource name= to override the field name
            name = (resource.name()!=null && !resource.name().trim().equals("")? resource.name(): name);

            //get the type of the Field
            Class type = f.getType();
            //if @Resource specifies a type, check it is compatible with field type
            if ((resource.type() != null)
                    && 
                    !resource.type().equals(Object.class)
                    &&
                    (!IntrospectionUtil.isTypeCompatible(type, resource.type(), false)))
                throw new IllegalStateException("@Resource incompatible type="+resource.type()+ " with field type ="+f.getType());

            //get the mappedName if there is one
            String mappedName = (resource.mappedName()!=null && !resource.mappedName().trim().equals("")?resource.mappedName():null);
            //get other parts that can be specified in @Resource
            Resource.AuthenticationType auth = resource.authenticationType();
            boolean shareable = resource.shareable();
            //check if an injection has already been setup for this target by web.xml
            Injection webXmlInjection = _injections.getInjection(f.getDeclaringClass(), f);
            if (webXmlInjection == null)
            {
                try
                {
                    boolean bound = org.eclipse.jetty.plus.jndi.NamingEntryUtil.bindToENC(_webApp, name, mappedName);
                    if (!bound)
                        bound = org.eclipse.jetty.plus.jndi.NamingEntryUtil.bindToENC(_webApp.getServer(), name, mappedName);
                    if (!bound)
                        bound =  org.eclipse.jetty.plus.jndi.NamingEntryUtil.bindToENC(null, name, mappedName); 
                    if (!bound)
                    {
                        //see if there is an env-entry value been bound from web.xml
                        try
                        {
                            InitialContext ic = new InitialContext();
                            String nameInEnvironment = (mappedName!=null?mappedName:name);
                            ic.lookup("java:comp/env/"+nameInEnvironment);                               
                            bound = true;
                        }
                        catch (NameNotFoundException e)
                        {
                            bound = false;
                        }
                    }
                    //Check there is a JNDI entry for this annotation 
                    if (bound)
                    { 
                        Log.debug("Bound "+(mappedName==null?name:mappedName) + " as "+ name);
                        //   Make the Injection for it if the binding succeeded
                        Injection injection = new Injection();
                        injection.setTargetClass(f.getDeclaringClass());
                        injection.setJndiName(name);
                        injection.setMappingName(mappedName);
                        injection.setTarget(f);
                        _injections.add(injection); 
                    }  
                    else if (!isEnvEntryType(type))
                    {
                        //if this is an env-entry type resource and there is no value bound for it, it isn't
                        //an error, it just means that perhaps the code will use a default value instead
                        // JavaEE Spec. sec 5.4.1.3

                        throw new IllegalStateException("No resource at "+(mappedName==null?name:mappedName));
                    }
                }
                catch (NamingException e)
                {
                    //if this is an env-entry type resource and there is no value bound for it, it isn't
                    //an error, it just means that perhaps the code will use a default value instead
                    // JavaEE Spec. sec 5.4.1.3
                    if (!isEnvEntryType(type))
                        throw new IllegalStateException(e);
                }
            }
            else
            {
                //if an injection is already set up for this name, then the types must be compatible
                //JavaEE spec sec 5.2.4
                Object value = webXmlInjection.lookupInjectedValue();
                if (!IntrospectionUtil.isTypeCompatible(type, value.getClass(), false))
                    throw new IllegalStateException("Type of field="+type+" is not compatible with Resource type="+value.getClass());
            }
        }
    }
    

    /**
     * Check if the presented method belongs to a class that is one
     * of the classes with which a servlet container should be concerned.
     * @param m
     * @return
     */
    private boolean isServletType (Class c)
    {    
        boolean isServlet = false;
        if (javax.servlet.Servlet.class.isAssignableFrom(c) ||
                javax.servlet.Filter.class.isAssignableFrom(c) || 
                javax.servlet.ServletContextListener.class.isAssignableFrom(c) ||
                javax.servlet.ServletContextAttributeListener.class.isAssignableFrom(c) ||
                javax.servlet.ServletRequestListener.class.isAssignableFrom(c) ||
                javax.servlet.ServletRequestAttributeListener.class.isAssignableFrom(c) ||
                javax.servlet.http.HttpSessionListener.class.isAssignableFrom(c) ||
                javax.servlet.http.HttpSessionAttributeListener.class.isAssignableFrom(c) || 
                (_pojoInstances.get(c) != null))

                isServlet=true;
        
        return isServlet;  
    }
    
   
    /**
     * Get an already-created instance of a pojo, or create one
     * otherwise.
     * @param clazz
     * @return
     * @throws InstantiationException
     * @throws IllegalAccessException
     */
    private Object getPojoInstanceFor (Class clazz) 
    throws InstantiationException, IllegalAccessException
    {
        Object instance = _pojoInstances.get(clazz);
        if (instance == null)
        {
            instance = clazz.newInstance();
            _pojoInstances.put(clazz, instance);
        }
        return instance;
    }

    private static boolean isEnvEntryType (Class type)
    {
        boolean result = false;
        for (int i=0;i<__envEntryTypes.length && !result;i++)
        {
            result = (type.equals(__envEntryTypes[i]));
        }
        return result;
    }
    
    protected static String normalizePattern(String p)
    {
        if (p!=null && p.length()>0 && !p.startsWith("/") && !p.startsWith("*"))
            return "/"+p;
        return p;
    }
}
