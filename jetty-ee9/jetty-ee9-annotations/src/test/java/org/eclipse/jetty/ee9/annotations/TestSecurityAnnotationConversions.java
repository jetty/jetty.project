//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
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

import java.util.Arrays;
import java.util.List;

import jakarta.servlet.annotation.HttpConstraint;
import jakarta.servlet.annotation.HttpMethodConstraint;
import jakarta.servlet.annotation.ServletSecurity;
import jakarta.servlet.annotation.ServletSecurity.EmptyRoleSemantic;
import jakarta.servlet.annotation.ServletSecurity.TransportGuarantee;
import jakarta.servlet.http.HttpServlet;
import org.eclipse.jetty.ee9.security.ConstraintAware;
import org.eclipse.jetty.ee9.security.ConstraintMapping;
import org.eclipse.jetty.ee9.security.ConstraintSecurityHandler;
import org.eclipse.jetty.ee9.servlet.ServletHolder;
import org.eclipse.jetty.ee9.servlet.ServletMapping;
import org.eclipse.jetty.ee9.webapp.WebAppContext;
import org.eclipse.jetty.util.security.Constraint;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

public class TestSecurityAnnotationConversions
{
    @ServletSecurity(value = @HttpConstraint(value = EmptyRoleSemantic.DENY))
    public static class DenyServlet extends HttpServlet
    {
    }

    @ServletSecurity
    public static class PermitServlet extends HttpServlet
    {
    }

    @ServletSecurity(value = @HttpConstraint(value = EmptyRoleSemantic.PERMIT, transportGuarantee = TransportGuarantee.CONFIDENTIAL, rolesAllowed =
        {
            "tom", "dick", "harry"
        }))
    public static class RolesServlet extends HttpServlet
    {
    }

    @ServletSecurity(value = @HttpConstraint(value = EmptyRoleSemantic.PERMIT, transportGuarantee = TransportGuarantee.CONFIDENTIAL, rolesAllowed =
        {
            "tom", "dick", "harry"
        }), httpMethodConstraints = {@HttpMethodConstraint(value = "GET")})
    public static class Method1Servlet extends HttpServlet
    {
    }

    @ServletSecurity(
        value = @HttpConstraint(
            value = EmptyRoleSemantic.PERMIT,
            transportGuarantee = TransportGuarantee.CONFIDENTIAL,
            rolesAllowed = {
                "tom", "dick", "harry"
            }),
        httpMethodConstraints = {
            @HttpMethodConstraint(value = "GET", transportGuarantee = TransportGuarantee.CONFIDENTIAL)
        })
    public static class Method2Servlet extends HttpServlet
    {
    }

    public void setUp()
    {
    }

    @Test
    public void testDenyAllOnClass() throws Exception
    {

        WebAppContext wac = makeWebAppContext(DenyServlet.class.getCanonicalName(), "denyServlet", new String[]{
            "/foo/*", "*.foo"
        });

        //Assume we found 1 servlet with a @HttpConstraint with value=EmptyRoleSemantic.DENY security annotation
        ServletSecurityAnnotationHandler annotationHandler = new ServletSecurityAnnotationHandler(wac);
        AnnotationIntrospector introspector = new AnnotationIntrospector(wac);
        introspector.registerHandler(annotationHandler);

        //set up the expected outcomes:
        //1 ConstraintMapping per ServletMapping pathSpec
        Constraint expectedConstraint = new Constraint();
        expectedConstraint.setAuthenticate(true);
        expectedConstraint.setDataConstraint(Constraint.DC_NONE);

        ConstraintMapping[] expectedMappings = new ConstraintMapping[2];

        expectedMappings[0] = new ConstraintMapping();
        expectedMappings[0].setConstraint(expectedConstraint);
        expectedMappings[0].setPathSpec("/foo/*");

        expectedMappings[1] = new ConstraintMapping();
        expectedMappings[1].setConstraint(expectedConstraint);
        expectedMappings[1].setPathSpec("*.foo");

        introspector.introspect(new DenyServlet(), null);

        compareResults(expectedMappings, ((ConstraintAware)wac.getSecurityHandler()).getConstraintMappings());
    }

    @Test
    public void testPermitAll() throws Exception
    {
        //Assume we found 1 servlet with a @ServletSecurity security annotation
        WebAppContext wac = makeWebAppContext(PermitServlet.class.getCanonicalName(), "permitServlet", new String[]{
            "/foo/*", "*.foo"
        });

        ServletSecurityAnnotationHandler annotationHandler = new ServletSecurityAnnotationHandler(wac);
        AnnotationIntrospector introspector = new AnnotationIntrospector(wac);
        introspector.registerHandler(annotationHandler);

        //set up the expected outcomes - no constraints at all as per Servlet Spec 3.1 pg 129
        //1 ConstraintMapping per ServletMapping pathSpec

        ConstraintMapping[] expectedMappings = new ConstraintMapping[]{};
        PermitServlet permit = new PermitServlet();
        introspector.introspect(permit, null);

        compareResults(expectedMappings, ((ConstraintAware)wac.getSecurityHandler()).getConstraintMappings());
    }

    @Test
    public void testRolesAllowedWithTransportGuarantee() throws Exception
    {
        //Assume we found 1 servlet with annotation with roles defined and
        //and a TransportGuarantee

        WebAppContext wac = makeWebAppContext(RolesServlet.class.getCanonicalName(), "rolesServlet", new String[]{
            "/foo/*", "*.foo"
        });

        ServletSecurityAnnotationHandler annotationHandler = new ServletSecurityAnnotationHandler(wac);
        AnnotationIntrospector introspector = new AnnotationIntrospector(wac);
        introspector.registerHandler(annotationHandler);

        //set up the expected outcomes:compareResults
        //1 ConstraintMapping per ServletMapping
        Constraint expectedConstraint = new Constraint();
        expectedConstraint.setAuthenticate(true);
        expectedConstraint.setRoles(new String[]{"tom", "dick", "harry"});
        expectedConstraint.setDataConstraint(Constraint.DC_CONFIDENTIAL);

        ConstraintMapping[] expectedMappings = new ConstraintMapping[2];
        expectedMappings[0] = new ConstraintMapping();
        expectedMappings[0].setConstraint(expectedConstraint);
        expectedMappings[0].setPathSpec("/foo/*");

        expectedMappings[1] = new ConstraintMapping();
        expectedMappings[1].setConstraint(expectedConstraint);
        expectedMappings[1].setPathSpec("*.foo");
        introspector.introspect(new RolesServlet(), null);
        compareResults(expectedMappings, ((ConstraintAware)wac.getSecurityHandler()).getConstraintMappings());
    }

    @Test
    public void testMethodAnnotation() throws Exception
    {
        //ServletSecurity annotation with HttpConstraint of TransportGuarantee.CONFIDENTIAL, and a list of rolesAllowed, and
        //an HttpMethodConstraint for GET method that permits all and has TransportGuarantee.NONE (ie is default)

        WebAppContext wac = makeWebAppContext(Method1Servlet.class.getCanonicalName(), "method1Servlet", new String[]{
            "/foo/*", "*.foo"
        });

        //set up the expected outcomes: - a Constraint for the RolesAllowed on the class
        //with userdata constraint of DC_CONFIDENTIAL
        //and mappings for each of the pathSpecs
        Constraint expectedConstraint1 = new Constraint();
        expectedConstraint1.setAuthenticate(true);
        expectedConstraint1.setRoles(new String[]{"tom", "dick", "harry"});
        expectedConstraint1.setDataConstraint(Constraint.DC_CONFIDENTIAL);

        //a Constraint for the PermitAll on the doGet method with a userdata
        //constraint of DC_CONFIDENTIAL inherited from the class
        Constraint expectedConstraint2 = new Constraint();
        expectedConstraint2.setDataConstraint(Constraint.DC_NONE);

        ConstraintMapping[] expectedMappings = new ConstraintMapping[4];
        expectedMappings[0] = new ConstraintMapping();
        expectedMappings[0].setConstraint(expectedConstraint1);
        expectedMappings[0].setPathSpec("/foo/*");
        expectedMappings[0].setMethodOmissions(new String[]{"GET"});
        expectedMappings[1] = new ConstraintMapping();
        expectedMappings[1].setConstraint(expectedConstraint1);
        expectedMappings[1].setPathSpec("*.foo");
        expectedMappings[1].setMethodOmissions(new String[]{"GET"});

        expectedMappings[2] = new ConstraintMapping();
        expectedMappings[2].setConstraint(expectedConstraint2);
        expectedMappings[2].setPathSpec("/foo/*");
        expectedMappings[2].setMethod("GET");
        expectedMappings[3] = new ConstraintMapping();
        expectedMappings[3].setConstraint(expectedConstraint2);
        expectedMappings[3].setPathSpec("*.foo");
        expectedMappings[3].setMethod("GET");

        AnnotationIntrospector introspector = new AnnotationIntrospector(wac);
        ServletSecurityAnnotationHandler annotationHandler = new ServletSecurityAnnotationHandler(wac);
        introspector.registerHandler(annotationHandler);
        introspector.introspect(new Method1Servlet(), null);
        compareResults(expectedMappings, ((ConstraintAware)wac.getSecurityHandler()).getConstraintMappings());
    }

    @Test
    public void testMethodAnnotation2() throws Exception
    {
        //A ServletSecurity annotation that has HttpConstraint of CONFIDENTIAL with defined roles, but a
        //HttpMethodConstraint for GET that permits all, but also requires CONFIDENTIAL
        WebAppContext wac = makeWebAppContext(Method2Servlet.class.getCanonicalName(), "method2Servlet", new String[]{
            "/foo/*", "*.foo"
        });

        AnnotationIntrospector introspector = new AnnotationIntrospector(wac);
        ServletSecurityAnnotationHandler annotationHandler = new ServletSecurityAnnotationHandler(wac);
        introspector.registerHandler(annotationHandler);

        //set up the expected outcomes: - a Constraint for the RolesAllowed on the class
        //with userdata constraint of DC_CONFIDENTIAL
        //and mappings for each of the pathSpecs
        Constraint expectedConstraint1 = new Constraint();
        expectedConstraint1.setAuthenticate(true);
        expectedConstraint1.setRoles(new String[]{"tom", "dick", "harry"});
        expectedConstraint1.setDataConstraint(Constraint.DC_CONFIDENTIAL);

        //a Constraint for the Permit on the GET method with a userdata
        //constraint of DC_CONFIDENTIAL
        Constraint expectedConstraint2 = new Constraint();
        expectedConstraint2.setDataConstraint(Constraint.DC_CONFIDENTIAL);

        ConstraintMapping[] expectedMappings = new ConstraintMapping[4];
        expectedMappings[0] = new ConstraintMapping();
        expectedMappings[0].setConstraint(expectedConstraint1);
        expectedMappings[0].setPathSpec("/foo/*");
        expectedMappings[0].setMethodOmissions(new String[]{"GET"});
        expectedMappings[1] = new ConstraintMapping();
        expectedMappings[1].setConstraint(expectedConstraint1);
        expectedMappings[1].setPathSpec("*.foo");
        expectedMappings[1].setMethodOmissions(new String[]{"GET"});

        expectedMappings[2] = new ConstraintMapping();
        expectedMappings[2].setConstraint(expectedConstraint2);
        expectedMappings[2].setPathSpec("/foo/*");
        expectedMappings[2].setMethod("GET");
        expectedMappings[3] = new ConstraintMapping();
        expectedMappings[3].setConstraint(expectedConstraint2);
        expectedMappings[3].setPathSpec("*.foo");
        expectedMappings[3].setMethod("GET");

        introspector.introspect(new Method2Servlet(), null);
        compareResults(expectedMappings, ((ConstraintAware)wac.getSecurityHandler()).getConstraintMappings());
    }

    private void compareResults(ConstraintMapping[] expectedMappings, List<ConstraintMapping> actualMappings)
    {
        assertNotNull(actualMappings);
        assertEquals(expectedMappings.length, actualMappings.size());

        for (int k = 0; k < actualMappings.size(); k++)
        {
            ConstraintMapping am = actualMappings.get(k);
            boolean matched = false;

            for (int i = 0; i < expectedMappings.length && !matched; i++)
            {
                ConstraintMapping em = expectedMappings[i];
                if (em.getPathSpec().equals(am.getPathSpec()))
                {
                    if ((em.getMethod() == null && am.getMethod() == null) || em.getMethod() != null && em.getMethod().equals(am.getMethod()))
                    {
                        matched = true;

                        assertEquals(em.getConstraint().getAuthenticate(), am.getConstraint().getAuthenticate());
                        assertEquals(em.getConstraint().getDataConstraint(), am.getConstraint().getDataConstraint());
                        if (em.getMethodOmissions() == null)
                        {
                            assertNull(am.getMethodOmissions());
                        }
                        else
                        {
                            assertTrue(Arrays.equals(am.getMethodOmissions(), em.getMethodOmissions()));
                        }

                        if (em.getConstraint().getRoles() == null)
                        {
                            assertNull(am.getConstraint().getRoles());
                        }
                        else
                        {
                            assertTrue(Arrays.equals(em.getConstraint().getRoles(), am.getConstraint().getRoles()));
                        }
                    }
                }
            }

            if (!matched)
                fail("No expected ConstraintMapping matching method:" + am.getMethod() + " pathSpec: " + am.getPathSpec());
        }
    }

    private WebAppContext makeWebAppContext(String className, String servletName, String[] paths)
    {
        WebAppContext wac = new WebAppContext();

        ServletHolder[] holders = new ServletHolder[1];
        holders[0] = new ServletHolder();
        holders[0].setClassName(className);
        holders[0].setName(servletName);
        holders[0].setServletHandler(wac.getServletHandler());
        wac.getServletHandler().setServlets(holders);
        wac.setSecurityHandler(new ConstraintSecurityHandler());

        ServletMapping[] servletMappings = new ServletMapping[1];
        servletMappings[0] = new ServletMapping();

        servletMappings[0].setPathSpecs(paths);
        servletMappings[0].setServletName(servletName);
        wac.getServletHandler().setServletMappings(servletMappings);
        return wac;
    }
}
