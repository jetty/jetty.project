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

import java.util.List;

import javax.naming.InitialContext;
import javax.naming.NameNotFoundException;
import javax.naming.NamingException;

import org.eclipse.jetty.annotations.AnnotationParser.AnnotationHandler;
import org.eclipse.jetty.annotations.AnnotationParser.Value;
import org.eclipse.jetty.plus.annotation.Injection;
import org.eclipse.jetty.plus.annotation.InjectionCollection;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.webapp.WebAppContext;

public class ResourceAnnotationHandler implements AnnotationHandler
{
    protected WebAppContext _wac;
    protected InjectionCollection _injections;

    public ResourceAnnotationHandler (WebAppContext wac)
    {
        _wac = wac;
        _injections = (InjectionCollection)_wac.getAttribute(InjectionCollection.INJECTION_COLLECTION);
    }


    /**
     *  Class level Resource annotations declare a name in the
     *  environment that will be looked up at runtime. They do
     *  not specify an injection.
     */
    public void handleClass(String className, int version, int access, String signature, String superName, String[] interfaces, String annotation,
                            List<Value> values)
    {
        String name = null;
        String mappedName = null;
        String resourceType = null;
        if (values != null)
        {
            for (Value v : values)
            {
                if ("name".equals(v.getName()))
                    name = (String)v.getValue();
                else if ("mappedName".equals(v.getName()))
                    mappedName = (String)v.getValue();
                else if ("type".equals(v.getName()))
                    resourceType = (String)v.getValue();
            }

            if (name==null || name.trim().equals(""))
                throw new IllegalStateException ("Class level Resource annotations must contain a name (Common Annotations Spec Section 2.3)");

            try
            {
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
        try
        {
            //JavaEE Spec 5.2.3: Field cannot be static
            if ((access & org.objectweb.asm.Opcodes.ACC_STATIC) > 0)
            {
                Log.warn("Skipping Resource annotation on "+className+"."+fieldName+": cannot be static");
                return;
            }

            //JavaEE Spec 5.2.3: Field cannot be final
            if ((access & org.objectweb.asm.Opcodes.ACC_FINAL) > 0)
            {
                Log.warn("Skipping Resource annotation on "+className+"."+fieldName+": cannot be final");
                return;
            }

            //work out default name
            String name = className+"/"+fieldName;
            String mappedName = null;
            org.objectweb.asm.Type resourceType = null;
            if (values != null)
            {
                for (Value val :values)
                {
                    //allow @Resource name= to override the field name
                    if (val.getName().equals("name") && !"".equals((String)val.getValue()))
                        name = (String)(val.getValue());
                    //get the mappedName if there is one
                    else if (val.getName().equals("mappedName") && !"".equals((String)val.getValue()))
                        mappedName = (String)val.getValue();
                    //save @Resource type, so we can check it is compatible with field type later
                    else if (val.getName().equals("type"))
                    {
                       resourceType = (org.objectweb.asm.Type)(val.getValue());
                    }
                }
            }
          
            //check if an injection has already been setup for this target by web.xml
            Injection webXmlInjection = _injections.getInjection(name, className, fieldName);
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
                        injection.setTarget(className, fieldName, Util.asCanonicalName(resourceType));
                        injection.setJndiName(name);
                        injection.setMappingName(mappedName);
                        _injections.add(injection); 
                    }  
                    else if (!Util.isEnvEntryType(fieldType))
                    {
                        System.err.println(fieldType);
                        
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
                    if (!Util.isEnvEntryType(fieldType))
                        throw new IllegalStateException(e);
                }
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
    public void handleMethod(String className, String methodName, int access, String desc, String signature, String[] exceptions, String annotation,
                             List<Value> values)
    {
        try
        {
          
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
            //JavaEE Spec 5.2.3: Method cannot be static
            if ((access & org.objectweb.asm.Opcodes.ACC_STATIC) > 0)
            {
                Log.warn("Skipping Resource annotation on "+className+"."+methodName+": cannot be static");
                return;
            }

            // Check it is a valid javabean: must be void return type, the name must start with "set" and it must have
            // only 1 parameter
            if (!methodName.startsWith("set"))
            {
                Log.warn("Skipping Resource annotation on "+className+"."+methodName+": invalid java bean, does not start with 'set'");
                return;
            }
            org.objectweb.asm.Type[] args = org.objectweb.asm.Type.getArgumentTypes(desc);
            if (args == null || args.length != 1)
            {
                Log.warn("Skipping Resource annotation on "+className+"."+methodName+": invalid java bean, not single argument to method");
                return; 
            }
            org.objectweb.asm.Type retVal = org.objectweb.asm.Type.getReturnType(desc);
            if (!org.objectweb.asm.Type.VOID_TYPE.equals(retVal))
            {
                Log.warn("Skipping Resource annotation on "+className+"."+methodName+": invalid java bean, not void");
                return; 
            }
        

            //default name is the javabean property name
            String name = methodName.substring(3);
            name = name.substring(0,1).toLowerCase()+name.substring(1);
            name = className+"/"+name;
            String mappedName = null;
            org.objectweb.asm.Type resourceType = null;
            if (values != null)
            {
                for (Value v : values)
                {
                    //allow default name to be overridden
                    if ("name".equals(v.getName()))
                        name = (String)(v.getValue());
                    //get the mappedName if there is one
                    else if ("mappedName".equals(v.getName()) && !"".equals((String)(v.getValue())))
                        mappedName = (String)(v.getValue());
                    else if ("type".equals(v.getName()))
                    {
                        resourceType = (org.objectweb.asm.Type)(v.getValue());
                    }
                    //TODO: authentication and shareable
                }
            }

            //check if an injection has already been setup for this target by web.xml
            Injection webXmlInjection = _injections.getInjection(name, className, methodName, Util.asCanonicalName(args[0]));
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
                        injection.setTarget(className, methodName, Util.asCanonicalName(args[0]), Util.asCanonicalName(resourceType));
                        injection.setJndiName(name);
                        injection.setMappingName(mappedName);
                        _injections.add(injection);
                    } 
                    else if (!Util.isEnvEntryType(args[0].getDescriptor()))
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
                    if (!Util.isEnvEntryType(args[0].getDescriptor()))
                        throw new IllegalStateException(e);
                }
            }
        }
        catch (Exception e)
        {
            Log.warn(e);
        }
    }
}
