//
//  ========================================================================
//  Copyright (c) 1995-2013 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.annotations;

import java.util.ArrayList;
import java.util.List;

import javax.servlet.ServletSecurityElement;
import javax.servlet.annotation.ServletSecurity;
import javax.servlet.annotation.ServletSecurity.EmptyRoleSemantic;
import javax.servlet.annotation.ServletSecurity.TransportGuarantee;

import org.eclipse.jetty.annotations.AnnotationIntrospector.AbstractIntrospectableAnnotationHandler;
import org.eclipse.jetty.security.ConstraintAware;
import org.eclipse.jetty.security.ConstraintMapping;
import org.eclipse.jetty.security.ConstraintSecurityHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.servlet.ServletMapping;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.security.Constraint;
import org.eclipse.jetty.webapp.Origin;
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
    private static final Logger LOG = Log.getLogger(ServletSecurityAnnotationHandler.class);

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
            LOG.warn("SecurityHandler not ConstraintAware, skipping security annotation processing");
            return;
        }

       ServletSecurity servletSecurity = (ServletSecurity)clazz.getAnnotation(ServletSecurity.class);
       if (servletSecurity == null)
           return;

       //If there are already constraints defined (ie from web.xml) that match any 
       //of the url patterns defined for this servlet, then skip the security annotation.

       List<ServletMapping> servletMappings = getServletMappings(clazz.getCanonicalName());
       List<ConstraintMapping> constraintMappings =  ((ConstraintAware)_context.getSecurityHandler()).getConstraintMappings();

       if (constraintsExist(servletMappings, constraintMappings))
       {
           LOG.warn("Constraints already defined for "+clazz.getName()+", skipping ServletSecurity annotation");
           return;
       }

       //Make a fresh list
       constraintMappings = new ArrayList<ConstraintMapping>();

       ServletSecurityElement securityElement = new ServletSecurityElement(servletSecurity);
       for (ServletMapping sm : servletMappings)
       {
           for (String url : sm.getPathSpecs())
           {
               _context.getMetaData().setOrigin("constraint.url."+url, Origin.Annotation);
               constraintMappings.addAll(ConstraintSecurityHandler.createConstraintsWithMappingsForPath(clazz.getName(), url, securityElement));
           }
       }

       //set up the security constraints produced by the annotation
       ConstraintAware securityHandler = (ConstraintAware)_context.getSecurityHandler();

       for (ConstraintMapping m:constraintMappings)
           securityHandler.addConstraintMapping(m);
       
       //Servlet Spec 3.1 requires paths with uncovered http methods to be reported
       securityHandler.checkPathsWithUncoveredHttpMethods();
    }



    /**
     * Make a jetty Constraint object, which represents the <auth-constraint> and
     * <user-data-constraint> elements, based on the security annotation.
     * @param servlet
     * @param rolesAllowed
     * @param permitOrDeny
     * @param transport
     */
    protected Constraint makeConstraint (Class servlet, String[] rolesAllowed, EmptyRoleSemantic permitOrDeny, TransportGuarantee transport)
    {
        return ConstraintSecurityHandler.createConstraint(servlet.getName(), rolesAllowed, permitOrDeny, transport);
    }



    /**
     * Get the ServletMappings for the servlet's class.
     * @param className
     */
    protected List<ServletMapping> getServletMappings(String className)
    {
        List<ServletMapping> results = new ArrayList<ServletMapping>();
        ServletMapping[] mappings = _context.getServletHandler().getServletMappings();
        for (ServletMapping mapping : mappings)
        {
            //Check the name of the servlet that this mapping applies to, and then find the ServletHolder for it to find it's class
            ServletHolder holder = _context.getServletHandler().getServlet(mapping.getServletName());
            if (holder.getClassName() != null && holder.getClassName().equals(className))
              results.add(mapping);
        }
        return results;
    }



    /**
     * Check if there are already <security-constraint> elements defined that match the url-patterns for
     * the servlet.
     * @param servletMappings
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
                   //TODO decide if we need to check the origin
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
