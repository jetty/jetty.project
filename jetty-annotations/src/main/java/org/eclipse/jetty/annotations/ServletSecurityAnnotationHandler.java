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
import java.util.List;

import javax.servlet.annotation.HttpConstraint;
import javax.servlet.annotation.HttpMethodConstraint;
import javax.servlet.annotation.ServletSecurity;
import javax.servlet.annotation.ServletSecurity.EmptyRoleSemantic;
import javax.servlet.annotation.ServletSecurity.TransportGuarantee;

import org.eclipse.jetty.annotations.AnnotationIntrospector.AbstractIntrospectableAnnotationHandler;
import org.eclipse.jetty.http.security.Constraint;
import org.eclipse.jetty.security.ConstraintAware;
import org.eclipse.jetty.security.ConstraintMapping;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.servlet.ServletMapping;
import org.eclipse.jetty.util.LazyList;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.webapp.WebAppContext;

/**
 * ServletSecurityAnnotationHandler
 *
 * Inspect a class to see if it has an @ServletSecurity annotation on it,
 * setting up the <security-constraint>s.
 * 
 * A servlet can be defined in:
 * <ul>
 * <li>web.xml
 * <li>web-fragment.xml
 * <li>@WebServlet annotation discovered
 * <li>ServletContext.createServlet
 * </ul>
 * 
 * The ServletSecurity annotation for a servlet should only be processed
 * iff metadata-complete == false.
 */
public class ServletSecurityAnnotationHandler extends AbstractIntrospectableAnnotationHandler
{

    private WebAppContext _context;
    
    public ServletSecurityAnnotationHandler(WebAppContext wac)
    {
        super(false);
        _context = wac;
    }
    
    /** 
     * @see org.eclipse.jetty.annotations.AnnotationIntrospector.IntrospectableAnnotationHandler#handle(java.lang.Class)
     */
    public void doHandle(Class clazz)
    {
        if (!(_context.getSecurityHandler() instanceof ConstraintAware))
        {
            Log.warn("SecurityHandler not ConstraintAware, skipping security annotation processing");
            return;
        }
        
       ServletSecurity servletSecurity = (ServletSecurity)clazz.getAnnotation(ServletSecurity.class);
       if (servletSecurity == null)
           return;
       
       //If there are already constraints defined (ie from web.xml or programmatically(?)) that match any 
       //of the url patterns defined for this servlet, then skip the security annotation.
      
       List<ServletMapping> servletMappings = getServletMappings(clazz.getCanonicalName());
       List<ConstraintMapping> constraintMappings =  ((ConstraintAware)_context.getSecurityHandler()).getConstraintMappings();
     
       if (constraintsExist(servletMappings, constraintMappings))
       {
           Log.warn("Constraints already defined for "+clazz.getName()+", skipping ServletSecurity annotation");
           return;
       }

       //Make a fresh list
       constraintMappings = new ArrayList<ConstraintMapping>();
       
       //Get the values that form the constraints that will apply unless there are HttpMethodConstraints to augment them
       HttpConstraint defaults = servletSecurity.value();

       //Make a Constraint for the <auth-constraint> and <user-data-constraint> specified by the HttpConstraint
       Constraint defaultConstraint = makeConstraint (clazz, 
                                                      defaults.rolesAllowed(), 
                                                      defaults.value(), 
                                                      defaults.transportGuarantee());

       constraintMappings.addAll(makeMethodMappings(clazz, 
                                                    defaultConstraint, 
                                                    servletMappings, 
                                                    servletSecurity.httpMethodConstraints()));

       //set up the security constraints produced by the annotation
       ConstraintAware securityHandler = (ConstraintAware)_context.getSecurityHandler();

       for (ConstraintMapping m:constraintMappings)
           securityHandler.addConstraintMapping(m); 
    }
    
 
    
    /**
     * Make a jetty Constraint object, which represents the <auth-constraint> and
     * <user-data-constraint> elements, based on the security annotation.
     * @param servlet
     * @param rolesAllowed
     * @param permitOrDeny
     * @param transport
     * @return
     */
    protected Constraint makeConstraint (Class servlet, String[] rolesAllowed, EmptyRoleSemantic permitOrDeny, TransportGuarantee transport)
    {  
        Constraint constraint = new Constraint();
        if (rolesAllowed == null || rolesAllowed.length==0)
        {           
           if (permitOrDeny.equals(EmptyRoleSemantic.DENY))
           {
               //Equivalent to <auth-constraint> with no roles
               constraint.setName(servlet.getName()+"-Deny");
               constraint.setAuthenticate(true);
           }
           else
           {
               //Equivalent to no <auth-constraint>
               constraint.setAuthenticate(false);
               constraint.setName(servlet.getName()+"-Permit");
           }
        }
        else
        {
            //Equivalent to <auth-constraint> with list of <security-role-name>s
            constraint.setAuthenticate(true);
            constraint.setRoles(rolesAllowed);
            constraint.setName(servlet.getName()+"-RolesAllowed");           
        } 
        
      //Equivalent to //<user-data-constraint><transport-guarantee>CONFIDENTIAL</transport-guarantee></user-data-constraint>
      constraint.setDataConstraint((transport.equals(TransportGuarantee.CONFIDENTIAL)?Constraint.DC_CONFIDENTIAL:Constraint.DC_NONE));
      return constraint;
    }
    
    
    /**
     * Make a ConstraintMapping which captures the <http-method> or <http-method-omission> elements for a particular url pattern,
     * and relates it to a Constraint object (<auth-constraint> and <user-data-constraint>).
     * @param constraint
     * @param url
     * @param method
     * @param omissions
     * @return
     */
    protected ConstraintMapping makeConstraintMapping (Constraint constraint, String url, String method, String[] omissions)
    {
        ConstraintMapping mapping = new ConstraintMapping();
        mapping.setConstraint(constraint);
        mapping.setPathSpec(url);
        if (method != null)
            mapping.setMethod(method);
        if (omissions != null)
            mapping.setMethodOmissions(omissions);
        return mapping;
    }
  
    /**
     * Make the Jetty Constraints and ConstraintMapping objects that correspond to the HttpMethodConstraint
     * annotations for each url pattern for the servlet.
     * @param servlet
     * @param defaultConstraint
     * @param servletMappings
     * @param annotations
     * @return
     */
    protected List<ConstraintMapping> makeMethodMappings (Class servlet, Constraint defaultConstraint, List<ServletMapping> servletMappings, HttpMethodConstraint[] annotations)
    {
        List<ConstraintMapping> mappings = new ArrayList<ConstraintMapping>();
        
        //for each url-pattern existing for the servlet make a ConstraintMapping for the HttpConstraint, and ConstraintMappings for
        //each HttpMethodConstraint
        for (ServletMapping sm : servletMappings)
        {
            for (String url : sm.getPathSpecs())
            {
                //Make a ConstraintMapping that matches the defaultConstraint
                ConstraintMapping defaultMapping = makeConstraintMapping(defaultConstraint, url, null, null);
                
                //If there are HttpMethodConstraint annotations, make a Constraint and a ConstraintMapping for it
                if (annotations != null && annotations.length>0)
                {    
                    List<String> omissions = new ArrayList<String>();
                    
                    //for each HttpMethodConstraint annotation, make a new Constraint and ConstraintMappings for this url
                    for (int i=0;  i < annotations.length;i++)
                    {
                        //Make a Constraint that captures the <auth-constraint> and <user-data-constraint> elements
                        Constraint methodConstraint = makeConstraint(servlet, 
                                                                     annotations[i].rolesAllowed(), 
                                                                     annotations[i].emptyRoleSemantic(),
                                                                     annotations[i].transportGuarantee());

                        //Make ConstraintMapping that captures the <http-method> elements                        
                        ConstraintMapping methodConstraintMapping = makeConstraintMapping (methodConstraint,
                                                                                           url,annotations[i].value(), 
                                                                                           null);
                        mappings.add(methodConstraintMapping);
                        omissions.add(annotations[i].value()); 
                    }   
                    defaultMapping.setMethodOmissions(omissions.toArray(new String[0]));
                }

                //add the constraint mapping containing the http-method-omissions, if there are any
                mappings.add(defaultMapping);
            }
        }             
        return mappings;
    }
    
   
    
    /**
     * Get the ServletMappings for the servlet's class.
     * @param className
     * @return
     */
    protected List<ServletMapping> getServletMappings(String className)
    {
        List<ServletMapping> results = new ArrayList<ServletMapping>();
        ServletMapping[] mappings = _context.getServletHandler().getServletMappings();
        for (ServletMapping mapping : mappings)
        {
            //Check the name of the servlet that this mapping applies to, and then find the ServletHolder for it to find it's class
            ServletHolder holder = _context.getServletHandler().getServlet(mapping.getServletName());
            if (holder.getClassName().equals(className))
              results.add(mapping);
        }
        return results;
    }
    
    
    
    /**
     * Check if there are already <security-constraint> elements defined that match the url-patterns for
     * the servlet.
     * @param servletMappings
     * @return
     */
    protected boolean constraintsExist (List<ServletMapping> servletMappings, List<ConstraintMapping> constraintMappings)
    {
        boolean exists = false;

        //Check to see if the path spec on each constraint mapping matches a pathSpec in the servlet mappings.
        //If it does, then we should ignore the security annotations.
        for (ServletMapping mapping : servletMappings)
        {  
            //Get its url mappings
            String[] pathSpecs = mapping.getPathSpecs();
            if (pathSpecs == null)
                continue;

            //Check through the constraints to see if there are any whose pathSpecs (url mappings)
            //match the servlet. If so, then we already have constraints defined for this servlet,
            //and we will not be processing the annotation (ie web.xml or programmatic override).
           for (int i=0; constraintMappings != null && i < constraintMappings.size() && !exists; i++)
           {
               for (int j=0; j < pathSpecs.length; j++)
               {
                   if (pathSpecs[j].equals(constraintMappings.get(i).getPathSpec()))
                   {
                       exists = true;
                       break;
                   }
               }
           }
        }      
        return exists;
    }

}
