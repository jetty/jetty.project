//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.ee9.annotations;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import jakarta.servlet.ServletSecurityElement;
import jakarta.servlet.annotation.ServletSecurity;
import jakarta.servlet.annotation.ServletSecurity.EmptyRoleSemantic;
import jakarta.servlet.annotation.ServletSecurity.TransportGuarantee;
import org.eclipse.jetty.ee9.annotations.AnnotationIntrospector.AbstractIntrospectableAnnotationHandler;
import org.eclipse.jetty.ee9.nested.ServletConstraint;
import org.eclipse.jetty.ee9.security.ConstraintAware;
import org.eclipse.jetty.ee9.security.ConstraintMapping;
import org.eclipse.jetty.ee9.security.ConstraintSecurityHandler;
import org.eclipse.jetty.ee9.servlet.ServletHolder;
import org.eclipse.jetty.ee9.servlet.ServletMapping;
import org.eclipse.jetty.ee9.webapp.WebAppContext;
import org.eclipse.jetty.util.StringUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Inspect a class to see if it has an <code>&#064;ServletSecurity</code> annotation on it,
 * setting up the <code>&lt;security-constraint&gt;s</code>.
 * <p>
 * A servlet can be defined in:
 * <ul>
 * <li>web.xml</li>
 * <li>web-fragment.xml</li>
 * <li>@WebServlet annotation discovered</li>
 * <li>ServletContext.createServlet</li>
 * </ul>
 *
 * The ServletSecurity annotation for a servlet should only be processed
 * iff metadata-complete == false.
 */
public class ServletSecurityAnnotationHandler extends AbstractIntrospectableAnnotationHandler
{
    private static final Logger LOG = LoggerFactory.getLogger(ServletSecurityAnnotationHandler.class);

    public ServletSecurityAnnotationHandler(WebAppContext wac)
    {
        super(false, wac);
    }

    @Override
    public void doHandle(Class<?> clazz)
    {
        if (!(_context.getSecurityHandler() instanceof ConstraintAware securityHandler))
        {
            LOG.warn("SecurityHandler not ConstraintAware, skipping security annotation processing");
            return;
        }

        ServletSecurity servletSecurity = clazz.getAnnotation(ServletSecurity.class);
        if (servletSecurity == null)
            return;

        List<ServletMapping> servletMappings = getServletMappings(clazz.getCanonicalName());

        Set<String> existingConstraintMappingPathSpecs = securityHandler.getConstraintMappings()
            .stream()
            .map(ConstraintMapping::getPathSpec)
            .filter(StringUtil::isNotBlank)
            .collect(Collectors.toSet());

        List<ConstraintMapping> constraintMappings = new ArrayList<>();

        ServletSecurityElement securityElement = new ServletSecurityElement(servletSecurity);
        for (ServletMapping sm : servletMappings)
        {
            for (String pathSpec : sm.getPathSpecs())
            {
                // Per Jakarta Servlet Spec 13.4.1: @ServletSecurity
                // The security-constraint elements defined in web.xml are authoritative for all exact match url-patterns.
                // https://jakarta.ee/specifications/servlet/5.0/jakarta-servlet-spec-5.0#servletsecurity-annotation
                // Note: for ee8 conversion, the rules here still apply on Jakarta Servlet 4.0 / EE8
                if (existingConstraintMappingPathSpecs.contains(pathSpec))
                {
                    LOG.warn("Constraint Mapping already defined for {} on path-spec {}, skipping ServletSecurity annotation", clazz.getName(), pathSpec);
                    continue; // skip
                }
                _context.getMetaData().setOrigin("constraint.url." + pathSpec, servletSecurity, clazz);
                constraintMappings.addAll(ConstraintSecurityHandler.createConstraintsWithMappingsForPath(clazz.getName(), pathSpec, securityElement));
            }
        }

        //set up the security constraints produced by the annotation
        constraintMappings.forEach(securityHandler::addConstraintMapping);

        //Servlet Spec 3.1 requires paths with uncovered http methods to be reported
        securityHandler.checkPathsWithUncoveredHttpMethods();
    }

    /**
     * Make a jetty Constraint object, which represents the <code>&lt;auth-constraint&gt;</code> and
     * <code>&lt;user-data-constraint&gt;</code> elements, based on the security annotation.
     *
     * @param servlet the servlet
     * @param rolesAllowed the roles allowed
     * @param permitOrDeny the role / permission semantic
     * @param transport the transport guarantee
     * @return the constraint
     */
    protected ServletConstraint makeConstraint(Class servlet, String[] rolesAllowed, EmptyRoleSemantic permitOrDeny, TransportGuarantee transport)
    {
        return ConstraintSecurityHandler.createConstraint(servlet.getName(), rolesAllowed, permitOrDeny, transport);
    }

    /**
     * Get the ServletMappings for the servlet's class.
     *
     * @param className the class name
     * @return the servlet mappings for the class
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
     * Check if there are already <code>&lt;security-constraint&gt;</code> elements defined that match the url-patterns for
     * the servlet.
     *
     * @param servletMappings the servlet mappings
     * @param constraintMappings the constraint mappings
     * @return true if constraint exists
     * @deprecated not used, no replacement
     */
    @Deprecated(since = "12.1.12", forRemoval = true)
    protected boolean constraintsExist(List<ServletMapping> servletMappings, List<ConstraintMapping> constraintMappings)
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
            for (int i = 0; constraintMappings != null && i < constraintMappings.size() && !exists; i++)
            {
                for (int j = 0; j < pathSpecs.length; j++)
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
