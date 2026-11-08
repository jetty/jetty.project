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

package org.eclipse.jetty.ee10.annotations;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import jakarta.servlet.ServletSecurityElement;
import jakarta.servlet.annotation.ServletSecurity;
import org.eclipse.jetty.ee10.annotations.AnnotationIntrospector.AbstractIntrospectableAnnotationHandler;
import org.eclipse.jetty.ee10.servlet.ServletHolder;
import org.eclipse.jetty.ee10.servlet.ServletMapping;
import org.eclipse.jetty.ee10.servlet.security.ConstraintAware;
import org.eclipse.jetty.ee10.servlet.security.ConstraintMapping;
import org.eclipse.jetty.ee10.servlet.security.ConstraintSecurityHandler;
import org.eclipse.jetty.ee10.webapp.WebAppContext;
import org.eclipse.jetty.util.StringUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p>Inspect a class to see if it has an <code>&#064;ServletSecurity</code> annotation on it,
 * setting up the <code>&lt;security-constraint&gt;s</code>.
 * </p>
 * <p>A servlet can be defined in:</p>
 * <ul>
 * <li>web.xml</li>
 * <li>web-fragment.xml</li>
 * <li>@WebServlet annotation discovered</li>
 * <li>ServletContext.createServlet</li>
 * </ul>
 * <p>
 * The ServletSecurity annotation for a servlet should only be processed
 * iff metadata-complete == false.</p>
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
                // https://jakarta.ee/specifications/servlet/6.0/jakarta-servlet-spec-6.0#servletsecurity-annotation
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
     * Get the ServletMappings for the servlet's class.
     *
     * @param className the class name
     * @return the servlet mappings for the class
     */
    protected List<ServletMapping> getServletMappings(String className)
    {
        List<ServletMapping> results = new ArrayList<>();
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
                for (String pathSpec : pathSpecs)
                {
                    //TODO decide if we need to check the origin
                    if (pathSpec.equals(constraintMappings.get(i).getPathSpec()))
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
