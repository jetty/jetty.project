// ========================================================================
// Copyright (c) 2009 Mort Bay Consulting Pty. Ltd.
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
import java.util.List;

import javax.annotation.Resource;
import javax.naming.InitialContext;
import javax.naming.NameNotFoundException;
import javax.naming.NamingException;

import org.eclipse.jetty.annotations.AnnotationParser.AnnotationHandler;
import org.eclipse.jetty.annotations.AnnotationParser.Value;
import org.eclipse.jetty.plus.annotation.Injection;
import org.eclipse.jetty.plus.annotation.InjectionCollection;
import org.eclipse.jetty.util.IntrospectionUtil;
import org.eclipse.jetty.util.Loader;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.webapp.WebAppContext;

public class ResourceAnnotationHandler implements AnnotationHandler
{
    protected WebAppContext _wac;

    public ResourceAnnotationHandler (WebAppContext wac)
    {
        _wac = wac;
    }


    /**
     *  Class level Resource annotations declare a name in the
     *  environment that will be looked up at runtime. They do
     *  not specify an injection.
     */
    public void handleClass(String className, int version, int access, String signature, String superName, String[] interfaces, String annotation,
                            List<Value> values)
    {
        Class clazz = null;
        try
        {
            clazz = Loader.loadClass(null, className);
        }
        catch (Exception e)
        {
            Log.warn(e);
        }
        if (!Util.isServletType(clazz))
        {
            Log.debug("Ignoring @Resource annotation on on-servlet type class "+clazz.getName());
            return;
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
                if (!org.eclipse.jetty.plus.jndi.NamingEntryUtil.bindToENC(_wac, name,mappedName))
                    if (!org.eclipse.jetty.plus.jndi.NamingEntryUtil.bindToENC(_wac.getServer(), name,mappedName))
                        throw new IllegalStateException("No resource at "+(mappedName==null?name:mappedName));
            }
            catch (NamingException e)
            {
                Log.warn(e);
            }
        }
    }

    public void handleField(String className, String fieldName, int access, String fieldType, String signature, Object value, String annotation,
                            List<Value> values)
    {
        InjectionCollection injections = (InjectionCollection)_wac.getAttribute(InjectionCollection.INJECTION_COLLECTION);
        Class clazz = null;
        try
        {
            clazz = Loader.loadClass(null, className);      
            Field f = clazz.getDeclaredField(fieldName);

            if (!Util.isServletType(clazz))
            {
                Log.debug("Ignoring @Resource annotation on on-servlet type field "+fieldName);
                return;
            }
            Resource resource = (Resource)f.getAnnotation(Resource.class);
            if (resource == null)
                return;

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
            Injection webXmlInjection = injections.getInjection(f.getDeclaringClass(), f);
            if (webXmlInjection == null)
            {
                try
                {
                    boolean bound = org.eclipse.jetty.plus.jndi.NamingEntryUtil.bindToENC(_wac, name, mappedName);
                    if (!bound)
                        bound = org.eclipse.jetty.plus.jndi.NamingEntryUtil.bindToENC(_wac.getServer(), name, mappedName);
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
                        injections.add(injection); 
                    }  
                    else if (!Util.isEnvEntryType(type))
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
                    if (!Util.isEnvEntryType(type))
                        throw new IllegalStateException(e);
                }
            }
            else
            {
                //if an injection is already set up for this name, then the types must be compatible
                //JavaEE spec sec 5.2.4
                Object val = webXmlInjection.lookupInjectedValue();
                if (!IntrospectionUtil.isTypeCompatible(type, value.getClass(), false))
                    throw new IllegalStateException("Type of field="+type+" is not compatible with Resource type="+val.getClass());
            }
        }
        catch (Exception e)
        {
            Log.warn(e);
        }
    }

    /**
     * Process a Resource annotation on a Method.
     * 
     * This will generate a JNDI entry, and an Injection to be
     * processed when an instance of the class is created.
     * @param injections
     */
    public void handleMethod(String className, String methodName, int access, String params, String signature, String[] exceptions, String annotation,
                             List<Value> values)
    {
        InjectionCollection injections = (InjectionCollection)_wac.getAttribute(InjectionCollection.INJECTION_COLLECTION);
        Class clazz = null;
        try
        {
            clazz = Loader.loadClass(null, className);

            Class[] args = Util.convertTypes(params); 
            Method m = clazz.getDeclaredMethod(methodName, args);

            if (!Util.isServletType(m.getDeclaringClass()))
            {
                Log.debug("Ignoring @Resource annotation on on-servlet type method "+m.getName());
                return;
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
                return;

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
            Injection webXmlInjection = injections.getInjection(m.getDeclaringClass(), m);
            if (webXmlInjection == null)
            {
                try
                {
                    //try binding name to environment
                    //try the webapp's environment first
                    boolean bound = org.eclipse.jetty.plus.jndi.NamingEntryUtil.bindToENC(_wac, name, mappedName);

                    //try the server's environment
                    if (!bound)
                        bound = org.eclipse.jetty.plus.jndi.NamingEntryUtil.bindToENC(_wac.getServer(), name, mappedName);

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
                        injections.add(injection);
                    } 
                    else if (!Util.isEnvEntryType(type))
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
                    if (!Util.isEnvEntryType(type))
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
        catch (Exception e)
        {
            Log.warn(e);
        }
    }
}
