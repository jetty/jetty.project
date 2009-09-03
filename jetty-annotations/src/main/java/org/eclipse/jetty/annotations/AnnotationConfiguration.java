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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;

import javax.servlet.ServletContainerInitializer;
import javax.servlet.annotation.HandlesTypes;

import org.eclipse.jetty.http.security.Constraint;
import org.eclipse.jetty.plus.annotation.AbstractAccessControl;
import org.eclipse.jetty.plus.annotation.ContainerInitializer;
import org.eclipse.jetty.plus.annotation.DenyAll;
import org.eclipse.jetty.plus.annotation.PermitAll;
import org.eclipse.jetty.plus.annotation.RolesAllowed;
import org.eclipse.jetty.plus.annotation.TransportProtected;
import org.eclipse.jetty.security.ConstraintAware;
import org.eclipse.jetty.security.ConstraintMapping;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.servlet.ServletMapping;
import org.eclipse.jetty.util.LazyList;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.webapp.WebAppContext;

/**
 * Configuration for Annotations
 *
 *
 */
public class AnnotationConfiguration extends AbstractConfiguration
{
    public static final String CLASS_INHERITANCE_MAP  = "org.eclipse.jetty.classInheritanceMap";
    
    public void preConfigure(final WebAppContext context) throws Exception
    {
    }
   
    
    public void configure(WebAppContext context) throws Exception
    {
       Boolean b = (Boolean)context.getAttribute(METADATA_COMPLETE);
       boolean metadataComplete = (b != null && b.booleanValue());
      
      
        if (metadataComplete)
        {
            //Never scan any jars or classes for annotations if metadata is complete
            if (Log.isDebugEnabled()) Log.debug("Metadata-complete==true,  not processing annotations for context "+context);
            return;
        }
        else 
        {
            //Only scan jars and classes if metadata is not complete and the web app is version 3.0, or
            //a 2.5 version webapp that has specifically asked to discover annotations
            if (Log.isDebugEnabled()) Log.debug("parsing annotations");
                       
            AnnotationParser parser = new AnnotationParser();
            parser.registerAnnotationHandler("javax.servlet.annotation.WebServlet", new WebServletAnnotationHandler(context));
            parser.registerAnnotationHandler("javax.servlet.annotation.WebFilter", new WebFilterAnnotationHandler(context));
            parser.registerAnnotationHandler("javax.servlet.annotation.WebListener", new WebListenerAnnotationHandler(context));
            parser.registerAnnotationHandler("javax.annotation.Resource", new ResourceAnnotationHandler (context));
            parser.registerAnnotationHandler("javax.annotation.Resources", new ResourcesAnnotationHandler (context));
            parser.registerAnnotationHandler("javax.annotation.PostConstruct", new PostConstructAnnotationHandler(context));
            parser.registerAnnotationHandler("javax.annotation.PreDestroy", new PreDestroyAnnotationHandler(context));
            parser.registerAnnotationHandler("javax.annotation.security.RunAs", new RunAsAnnotationHandler(context));
            parser.registerAnnotationHandler("javax.annotation.security.DenyAll", new DenyAllAnnotationHandler(context));
            parser.registerAnnotationHandler("javax.annotation.security.PermitAll", new PermitAllAnnotationHandler(context));
            parser.registerAnnotationHandler("javax.annotation.security.RolesAllowed", new RolesAllowedAnnotationHandler(context));
            parser.registerAnnotationHandler("javax.annotation.security.TransportProtected", new TransportProtectedAnnotationHandler(context));
            ClassInheritanceHandler classHandler = new ClassInheritanceHandler();
            parser.registerClassHandler(classHandler);
            registerServletContainerInitializerAnnotationHandlers(context, parser);
            
            if (context.getServletContext().getEffectiveMajorVersion() >= 3 || context.isConfigurationDiscovered())
            {
                if (Log.isDebugEnabled()) Log.debug("Scanning all classses for annotations: webxmlVersion="+context.getServletContext().getEffectiveMajorVersion()+" configurationDiscovered="+context.isConfigurationDiscovered());
                parseContainerPath(context, parser);
                parseWebInfLib (context, parser);
                parseWebInfClasses(context, parser);
            } 
            else
            {
                if (Log.isDebugEnabled()) Log.debug("Scanning only classes in web.xml for annotations");
                parse25Classes(context, parser);
            }
            
            //save the type inheritance map created by the parser for later reference
            context.setAttribute(CLASS_INHERITANCE_MAP, classHandler.getMap());
        }    
    }



    public void deconfigure(WebAppContext context) throws Exception
    {
        
    }




    public void postConfigure(WebAppContext context) throws Exception
    {
        if (!(context.getSecurityHandler() instanceof ConstraintAware))
        {
            Log.warn("SecurityHandler not ConstraintAware, skipping security annotation processing");
            return;
        }
        ConstraintAware securityHandler = (ConstraintAware)context.getSecurityHandler();
        List<ConstraintMapping> constraintMappings = LazyList.array2List(securityHandler.getConstraintMappings());
        
        //process Security Annotations
        
        /* GregW proposed spec wording for section 13.4
         * 
         * "When a security-constraint in the portable deployment descriptor includes a
         * url-pattern that exactly matches a pattern specified either in a @WebServlet
         * annotation or in a servlet-mapping descriptor, then any associated security
         * annotations described in this section MUST have no effect on the access
         * policy enforced by the container."
         */
        
        //This would mean that:
        // We search the Constraints already defined from web.xml.
        // If the pathSpec of the Constraint exactly matches a ServletMapping pathSpec,
        // then we skip the annotation.
        // Otherwise, we make a new Constraint. We add a pathSpec to it for each ServletMapping.pathSpec
        // that matches the class that was annotated. Then we add an auth-constraint clause for every
        // RolesAllowed and DenyAll annotation (PermitAll has no auth-constraint associated with it).
        // For TransportProtected we add a user-data-constraint. If there are any method-level
        // security annotations, then we add http-omission clauses corresponding to the doX method
        // that they are specified on.
        //
        // Then for each method-level security annotation, we add a separate Constraint, with the same
        // set of pathSpecs derived from the ServletMapping.pathSpecs, but with http-method clauses in 
        // it.
        
        
        Map<String, List<AbstractAccessControl>> securityAnnotations = (Map<String, List<AbstractAccessControl>>) context.getAttribute(AbstractSecurityAnnotationHandler.SECURITY_ANNOTATIONS);
        for (Map.Entry<String, List<AbstractAccessControl>> e: securityAnnotations.entrySet())
        {
            boolean matched = false;

            //Get the servlet-mappings that match the annotated classname
            List<ServletMapping> servletMappings = getServletMappingsForServlet(context, e.getKey());
            //Check to see if the path spec on each constraint mapping matches a pathSpec in the servlet mappings.
            //If it does, then we should ignore the security annotations.
            for (ServletMapping mapping : servletMappings)
            {  
                //Get its url mappings
                String[] pathSpecs = mapping.getPathSpecs();
                if (pathSpecs == null)
                    continue;


                //If at least one of the url patterns for the servlet-mappings for the servlet class
                //is in a security constraint, then we ignore all security annotations for this class.
               for (int i=0; constraintMappings != null && i < constraintMappings.size() && !matched; i++)
               {
                   for (int j=0; j < pathSpecs.length; j++)
                   {
                       if (pathSpecs[j].equals(constraintMappings.get(i).getPathSpec()))
                       {
                           matched = true;
                           break;
                       }
                   }
               }
            }

            if (!matched)
            { 
                List<AbstractAccessControl> aacList = e.getValue();
                
                //Divide into class-targeted and method-targeted annotations
                List<AbstractAccessControl> classAacList = new ArrayList<AbstractAccessControl>();
                Map<String, List<AbstractAccessControl>> methodAacMap = new HashMap<String,List<AbstractAccessControl>>();
                
                for (AbstractAccessControl a : aacList)
                {
                    if (a.getMethodName() != null)
                    {
                        List<AbstractAccessControl> methodList = methodAacMap.get(a.getMethodName());
                        if (methodList == null)
                        {
                            methodList = new ArrayList<AbstractAccessControl>();
                            methodAacMap.put(a.getMethodName(), methodList);
                        }
                        methodList.add(a);
                    }
                    else
                        classAacList.add(a);
                }

                //There will only be at most 2 elements in this list: one of RolesAllowed,PermitAll,DenyAll and 
                //then optionally the TransportProtected annotation.
                Constraint classConstraint = new Constraint();
                configureConstraint (classConstraint, classAacList);
                constraintMappings.addAll(makeConstraintMappings(context, classConstraint, servletMappings, methodAacMap));
            }
        }

        //Set up all of the constraint mappings representing web-resource-collections
        securityHandler.setConstraintMappings((ConstraintMapping[]) LazyList.toArray(constraintMappings,ConstraintMapping.class),securityHandler.getRoles());
        context.setAttribute(CLASS_INHERITANCE_MAP, null);
    }
    

    public void registerServletContainerInitializerAnnotationHandlers (WebAppContext context, AnnotationParser parser)
    {     
        //Get all ServletContainerInitializers, and check them for HandlesTypes annotations.
        //For each class in the HandlesTypes value, if it IS an annotation, register a handler
        //that will record the classes that have that annotation.
        //If it is NOT an annotation, then we will interrogate the type hierarchy discovered during
        //parsing later on to find the applicable classes.
        ArrayList<ContainerInitializer> initializers = new ArrayList<ContainerInitializer>();
        context.setAttribute(ContainerInitializerConfiguration.CONTAINER_INITIALIZERS, initializers);
        
        //We use the ServiceLoader mechanism to find the ServletContainerInitializer classes to inspect
        ServiceLoader<ServletContainerInitializer> loadedInitializers = ServiceLoader.load(ServletContainerInitializer.class);
        if (loadedInitializers != null)
        {
            for (ServletContainerInitializer i : loadedInitializers)
            {
                HandlesTypes annotation = i.getClass().getAnnotation(HandlesTypes.class);
                ContainerInitializer initializer = new ContainerInitializer();
                initializer.setTarget(i);
                initializers.add(initializer);
                if (annotation != null)
                {
                    Class[] classes = annotation.value();
                    if (classes != null)
                    {
                        initializer.setInterestedTypes(classes);
                        for (Class c: classes)
                        {
                            if (c.isAnnotation())
                            {
                                if (Log.isDebugEnabled()) Log.debug("Registering annotation handler for "+c.getName());
                                parser.registerAnnotationHandler(c.getName(), new ContainerInitializerAnnotationHandler(initializer, c));
                            }
                        }
                    }
                    else
                        Log.info("No classes in HandlesTypes on initializer "+i.getClass());
                }
                else
                    Log.info("No annotation on initializer "+i.getClass());
            }
        }
    }
    
    protected List<ServletMapping> getServletMappingsForServlet(WebAppContext context, String className)
    {
        List<ServletMapping> results = new ArrayList<ServletMapping>();
        ServletMapping[] mappings = context.getServletHandler().getServletMappings();
        for (ServletMapping mapping : mappings)
        {
            //Check the name of the servlet that this mapping applies to, and then find the ServletHolder for it to find it's class
            ServletHolder holder = context.getServletHandler().getServlet(mapping.getServletName());
            if (holder.getClassName().equals(className))
              results.add(mapping);
        }
        return results;
    }
    
    
    /**
     * Given a set of ServletMappings (url-patterns) make ConstraintMappings (web-resource-collections)
     * for the security contraint.
     * @param context
     * @param classConstraint
     * @param servletMappings
     * @param methodAacMap
     * @return
     */
    protected List<ConstraintMapping> makeConstraintMappings (WebAppContext context, Constraint classConstraint, List<ServletMapping> servletMappings, Map<String, List<AbstractAccessControl>> methodAacMap)
    {
        List<ConstraintMapping> constraintMappings = new ArrayList<ConstraintMapping>();
        
        //for each servlet mapping, add a constraint mapping
        for (ServletMapping sm : servletMappings)
        {
            for (String url : sm.getPathSpecs())
            {
                ConstraintMapping mapping = new ConstraintMapping();
                mapping.setConstraint(classConstraint);
                mapping.setPathSpec(url);
                List<String> omissions = new ArrayList<String>();
                if (!methodAacMap.isEmpty())
                {
                    //Process the method-level annotations which will require http-method-omission
                    //statements
                    for (String method: methodAacMap.keySet())
                    {
                        omissions.add(method);
                       
                        //Make a new Constraint that matches the security annotation (PermitAll, DenyAll, RolesAllowed)
                        //on the method (which may also optionally have the TransportProtected annotation)
                        Constraint methodConstraint = new Constraint(); 
                        configureConstraint (methodConstraint, methodAacMap.get(method));    

                        //Add a new constraint mapping for the same url as part of the web-resource-collection, but set the
                        //method-name
                        ConstraintMapping methodMapping = new ConstraintMapping();
                        methodMapping.setConstraint(methodConstraint);
                        methodMapping.setPathSpec(url);
                        methodMapping.setMethod(method);
                        constraintMappings.add(methodMapping);

                        //If the security annotations on the method do not include @TransportProtected
                        //then inherit the setting from the class level
                        if (methodConstraint.getDataConstraint() == Constraint.DC_UNSET)
                            methodConstraint.setDataConstraint(classConstraint.getDataConstraint());
                    }
                }
                //Add the constraint mapping for the class-level annotation
                constraintMappings.add(mapping);
                //Add a http-method-omission on the Constraint for the class-level annotation
                if (!omissions.isEmpty())
                    mapping.setMethodOmissions(omissions.toArray(new String[0]));
            }
        }
        return constraintMappings;
    }
    
    /**
     * Set up the Constraint to represent the auth-constraint and user-data-constraint.
     * 
     * @param constraint
     * @param aacList
     */
    protected void configureConstraint (Constraint constraint, List<AbstractAccessControl> aacList)
    {
        if (aacList != null)
        {
            for (AbstractAccessControl aac: aacList)
            {
                if (aac instanceof RolesAllowed)
                {
                    //Equivalent to <auth-constraint> with list of <role-name>s
                    constraint.setAuthenticate(true);
                    constraint.setRoles(((RolesAllowed)aac).getRoles());
                    constraint.setName(aac.getClass().getName()+"-"+(aac.getMethodName()==null?"":aac.getMethodName())+"-RolesAllowed");
                }
                else if (aac instanceof PermitAll)
                {
                    //Equivalent to no <auth-constraint>
                    constraint.setAuthenticate(false);
                    constraint.setName(aac.getClass().getName()+"-"+(aac.getMethodName()==null?"":aac.getMethodName())+"-PermitAll");
                }
                else if (aac instanceof DenyAll)
                {
                    //Equivalent to <auth-constraint> with no roles
                    constraint.setName(aac.getClass().getName()+"-"+(aac.getMethodName()==null?"":aac.getMethodName())+"-DenyAll");
                    constraint.setAuthenticate(true);
                }
                else if (aac instanceof TransportProtected)
                {
                    constraint.setDataConstraint(((TransportProtected)aac).getValue()?Constraint.DC_CONFIDENTIAL:Constraint.DC_NONE);
                }
            }
        }
    }
}
