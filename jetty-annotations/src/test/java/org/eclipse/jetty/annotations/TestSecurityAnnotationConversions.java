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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.jetty.http.security.Constraint;
import org.eclipse.jetty.plus.annotation.AbstractAccessControl;
import org.eclipse.jetty.plus.annotation.DenyAll;
import org.eclipse.jetty.plus.annotation.PermitAll;
import org.eclipse.jetty.plus.annotation.RolesAllowed;
import org.eclipse.jetty.plus.annotation.TransportProtected;
import org.eclipse.jetty.security.ConstraintAware;
import org.eclipse.jetty.security.ConstraintMapping;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.servlet.ServletMapping;
import org.eclipse.jetty.util.LazyList;
import org.eclipse.jetty.webapp.WebAppContext;

import junit.framework.TestCase;

public class TestSecurityAnnotationConversions extends TestCase
{
    WebAppContext _wac;
    ServletHolder[] _holders;
    ServletMapping[] _servletMappings;
    String[] _paths = new String[2];
    
    public void setUp()
    {
        _wac = new WebAppContext();   
        _holders = new ServletHolder[1];
        _holders[0] = new ServletHolder();
        _holders[0].setClassName("com.acme.FooServlet");
        _holders[0].setName("fooServlet");
        _holders[0].setServletHandler(_wac.getServletHandler());
        _wac.getServletHandler().setServlets(_holders);
        
        _servletMappings = new ServletMapping[1];
        _servletMappings[0] = new ServletMapping();
      
        _paths[0] = "/foo/*";
        _paths[1] = "*.foo";
        _servletMappings[0].setPathSpecs(_paths);
        _servletMappings[0].setServletName("fooServlet");
        _wac.getServletHandler().setServletMappings(_servletMappings);  
    }
    
    public void testDenyAllOnClass ()
    throws Exception
    {
        //Assume we found 1 servlet with a @DenyAll security annotation
        DenyAll denyAll = new DenyAll("com.acme.FooServlet");
        Map<String, List<AbstractAccessControl>> accessControlMap  = new HashMap<String, List<AbstractAccessControl>>();
        List<AbstractAccessControl> list = new ArrayList<AbstractAccessControl>();
        list.add(denyAll);
        accessControlMap.put("com.acme.FooServlet", list);
        _wac.setAttribute(AbstractSecurityAnnotationHandler.SECURITY_ANNOTATIONS, accessControlMap);
        
        //set up the expected outcomes:
        //1 ConstraintMapping per ServletMapping pathSpec
        Constraint expectedConstraint = new Constraint();
        expectedConstraint.setAuthenticate(true);
        
        ConstraintMapping[] expectedMappings = new ConstraintMapping[2];
        
        expectedMappings[0] = new ConstraintMapping();
        expectedMappings[0].setConstraint(expectedConstraint);
        expectedMappings[0].setPathSpec("/foo/*");
        
        expectedMappings[1] = new ConstraintMapping();
        expectedMappings[1].setConstraint(expectedConstraint);
        expectedMappings[1].setPathSpec("*.foo");
        
        AnnotationConfiguration config = new AnnotationConfiguration();
        config.postConfigure(_wac);
        compareResults(expectedMappings, ((ConstraintAware)_wac.getSecurityHandler()).getConstraintMappings());   
    }
    
    public void testPermitAllOnClass()
    throws Exception
    {
        //Assume we found 1 servlet with a @PermitAll security annotation
       
        PermitAll permitAll = new PermitAll("com.acme.FooServlet");
       
        Map<String, List<AbstractAccessControl>> accessControlMap  = new HashMap<String, List<AbstractAccessControl>>();
        List<AbstractAccessControl> list = new ArrayList<AbstractAccessControl>();
        list.add(permitAll);
        
        accessControlMap.put("com.acme.FooServlet", list);
        _wac.setAttribute(AbstractSecurityAnnotationHandler.SECURITY_ANNOTATIONS, accessControlMap);
        //set up the expected outcomes:
        //1 ConstraintMapping per ServletMapping pathSpec
        Constraint expectedConstraint = new Constraint();
        expectedConstraint.setAuthenticate(false);
       
        
        ConstraintMapping[] expectedMappings = new ConstraintMapping[2];
        expectedMappings[0] = new ConstraintMapping();
        expectedMappings[0].setConstraint(expectedConstraint);
        expectedMappings[0].setPathSpec("/foo/*");
        
        expectedMappings[1] = new ConstraintMapping();
        expectedMappings[1].setConstraint(expectedConstraint);
        expectedMappings[1].setPathSpec("*.foo");

        AnnotationConfiguration config = new AnnotationConfiguration();
        config.postConfigure(_wac);
        compareResults (expectedMappings, ((ConstraintAware)_wac.getSecurityHandler()).getConstraintMappings());
    }
    
    public void testClassAnnotationWithTransportProtected ()
    throws Exception
    {
        //Assume we found 1 servlet with a @RolesAllowed security annotation
        //and a @TransportProtected annotation on the class
        RolesAllowed rolesAllowed = new RolesAllowed("com.acme.FooServlet");
        rolesAllowed.setRoles(new String[]{"tom", "dick", "harry"});
        TransportProtected transportProtected = new TransportProtected("com.acme.FooServlet");
        transportProtected.setValue(true);
        
        Map<String, List<AbstractAccessControl>> accessControlMap  = new HashMap<String, List<AbstractAccessControl>>();
        List<AbstractAccessControl> list = new ArrayList<AbstractAccessControl>();
        list.add(rolesAllowed);
        list.add(transportProtected);
        
        accessControlMap.put("com.acme.FooServlet", list);
        _wac.setAttribute(AbstractSecurityAnnotationHandler.SECURITY_ANNOTATIONS, accessControlMap);
        //set up the expected outcomes:
        //1 ConstraintMapping per ServletMapping pathSpec
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
        
        AnnotationConfiguration config = new AnnotationConfiguration();
        config.postConfigure(_wac);
        compareResults (expectedMappings, ((ConstraintAware)_wac.getSecurityHandler()).getConstraintMappings());
    }
  
    public void testClassAnnotationWithTransportProtectedAndMethodAnnotation ()
    throws Exception
    {
        //Assume we found 1 servlet with a @RolesAllowed security annotation
        //and a @TransportProtected annotation on the class and a @PermitAll
        //annotation on the GET method
        RolesAllowed rolesAllowed = new RolesAllowed("com.acme.FooServlet");
        rolesAllowed.setRoles(new String[]{"tom", "dick", "harry"});
        TransportProtected transportProtected = new TransportProtected("com.acme.FooServlet");
        transportProtected.setValue(true);
        PermitAll permitAll = new PermitAll("com.acme.FooServlet");
        permitAll.setMethodName("doGet");
        
        Map<String, List<AbstractAccessControl>> accessControlMap  = new HashMap<String, List<AbstractAccessControl>>();
        List<AbstractAccessControl> list = new ArrayList<AbstractAccessControl>();
        list.add(rolesAllowed);
        list.add(permitAll);
        list.add(transportProtected);
        
        accessControlMap.put("com.acme.FooServlet", list);
        _wac.setAttribute(AbstractSecurityAnnotationHandler.SECURITY_ANNOTATIONS, accessControlMap);
        
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
        expectedConstraint2.setDataConstraint(Constraint.DC_CONFIDENTIAL);
        
        ConstraintMapping[] expectedMappings = new ConstraintMapping[4];
        expectedMappings[0] = new ConstraintMapping();
        expectedMappings[0].setConstraint(expectedConstraint1);
        expectedMappings[0].setPathSpec("/foo/*");
        expectedMappings[0].setMethodOmissions(new String[]{"doGet"});
        expectedMappings[1] = new ConstraintMapping();
        expectedMappings[1].setConstraint(expectedConstraint1);
        expectedMappings[1].setPathSpec("*.foo"); 
        expectedMappings[1].setMethodOmissions(new String[]{"doGet"});
        
        expectedMappings[2] = new ConstraintMapping();
        expectedMappings[2].setConstraint(expectedConstraint2);
        expectedMappings[2].setPathSpec("/foo/*");
        expectedMappings[2].setMethod("doGet");
        expectedMappings[3] = new ConstraintMapping();
        expectedMappings[3].setConstraint(expectedConstraint2);
        expectedMappings[3].setPathSpec("*.foo");
        expectedMappings[3].setMethod("doGet");
        
        AnnotationConfiguration config = new AnnotationConfiguration();
        config.postConfigure(_wac);
        compareResults (expectedMappings, ((ConstraintAware)_wac.getSecurityHandler()).getConstraintMappings());
    }
   
    public void testClassAnnotationAndMethodAnnotationWithTransportProtected ()
    throws Exception
    {
        //Assume we found 1 servlet with a @RolesAllowed security annotation
        //and a @PermitAll annotation on the GET method with TransportProtected
        RolesAllowed rolesAllowed = new RolesAllowed("com.acme.FooServlet");
        rolesAllowed.setRoles(new String[]{"tom", "dick", "harry"});
        
        PermitAll permitAll = new PermitAll("com.acme.FooServlet");
        permitAll.setMethodName("doGet");
        
        TransportProtected transportProtected = new TransportProtected("com.acme.FooServlet");
        transportProtected.setMethodName("doGet");
        transportProtected.setValue(true);
        
        Map<String, List<AbstractAccessControl>> accessControlMap  = new HashMap<String, List<AbstractAccessControl>>();
        List<AbstractAccessControl> list = new ArrayList<AbstractAccessControl>();
        list.add(rolesAllowed);
        list.add(permitAll);
        list.add(transportProtected);
        
        accessControlMap.put("com.acme.FooServlet", list);
        _wac.setAttribute(AbstractSecurityAnnotationHandler.SECURITY_ANNOTATIONS, accessControlMap);
        
        //set up the expected outcomes: - a Constraint for the RolesAllowed on the class
        //with userdata constraint of DC_UNSET 
        //and mappings for each of the pathSpecs
        Constraint expectedConstraint1 = new Constraint();
        expectedConstraint1.setAuthenticate(true);
        expectedConstraint1.setRoles(new String[]{"tom", "dick", "harry"});
     
        //a Constraint for the PermitAll on the doGet method with a userdata
        //constraint of DC_CONFIDENTIAL 
        Constraint expectedConstraint2 = new Constraint();  
        expectedConstraint2.setDataConstraint(Constraint.DC_CONFIDENTIAL);
        
        ConstraintMapping[] expectedMappings = new ConstraintMapping[4];
        expectedMappings[0] = new ConstraintMapping();
        expectedMappings[0].setConstraint(expectedConstraint1);
        expectedMappings[0].setPathSpec("/foo/*");
        expectedMappings[0].setMethodOmissions(new String[]{"doGet"});
        expectedMappings[1] = new ConstraintMapping();
        expectedMappings[1].setConstraint(expectedConstraint1);
        expectedMappings[1].setPathSpec("*.foo"); 
        expectedMappings[1].setMethodOmissions(new String[]{"doGet"});
        
        expectedMappings[2] = new ConstraintMapping();
        expectedMappings[2].setConstraint(expectedConstraint2);
        expectedMappings[2].setPathSpec("/foo/*");
        expectedMappings[2].setMethod("doGet");
        expectedMappings[3] = new ConstraintMapping();
        expectedMappings[3].setConstraint(expectedConstraint2);
        expectedMappings[3].setPathSpec("*.foo");
        expectedMappings[3].setMethod("doGet");
        
        AnnotationConfiguration config = new AnnotationConfiguration();
        config.postConfigure(_wac);
        compareResults (expectedMappings, ((ConstraintAware)_wac.getSecurityHandler()).getConstraintMappings());
    }
    
    public void testClassAnnotationWithTransportProtectedAndMethodAnnotationWithTransportProtected ()
    throws Exception
    {
        //Assume we found 1 servlet with a @RolesAllowed security annotation and a @TransportProtected annotation
        //and a @PermitAll annotation on the GET method with TransportProtected
        RolesAllowed rolesAllowed = new RolesAllowed("com.acme.FooServlet");
        rolesAllowed.setRoles(new String[]{"tom", "dick", "harry"});
        TransportProtected transportProtectedClass = new TransportProtected("com.acme.FooServlet");
        transportProtectedClass.setValue(true);
        PermitAll permitAll = new PermitAll("com.acme.FooServlet");
        permitAll.setMethodName("doGet");      
        TransportProtected transportProtectedMethod = new TransportProtected("com.acme.FooServlet");
        transportProtectedMethod.setMethodName("doGet");
        transportProtectedMethod.setValue(false);
        
        Map<String, List<AbstractAccessControl>> accessControlMap  = new HashMap<String, List<AbstractAccessControl>>();
        List<AbstractAccessControl> list = new ArrayList<AbstractAccessControl>();
        list.add(rolesAllowed);
        list.add(permitAll);
        list.add(transportProtectedClass);
        list.add(transportProtectedMethod);
        accessControlMap.put("com.acme.FooServlet", list);
        _wac.setAttribute(AbstractSecurityAnnotationHandler.SECURITY_ANNOTATIONS, accessControlMap);
        
        //set up the expected outcomes: - a Constraint for the RolesAllowed on the class
        //with userdata constraint of DC_CONFIDENTIAL 
        //and mappings for each of the pathSpecs
        Constraint expectedConstraint1 = new Constraint();
        expectedConstraint1.setAuthenticate(true);
        expectedConstraint1.setRoles(new String[]{"tom", "dick", "harry"});
        expectedConstraint1.setDataConstraint(Constraint.DC_CONFIDENTIAL);
     
        //a Constraint for the PermitAll on the doGet method with a userdata
        //constraint of DC_INTEGRAL
        Constraint expectedConstraint2 = new Constraint();  
        expectedConstraint2.setDataConstraint(Constraint.DC_NONE);
        
        ConstraintMapping[] expectedMappings = new ConstraintMapping[4];
        expectedMappings[0] = new ConstraintMapping();
        expectedMappings[0].setConstraint(expectedConstraint1);
        expectedMappings[0].setPathSpec("/foo/*");
        expectedMappings[0].setMethodOmissions(new String[]{"doGet"});
        expectedMappings[1] = new ConstraintMapping();
        expectedMappings[1].setConstraint(expectedConstraint1);
        expectedMappings[1].setPathSpec("*.foo"); 
        expectedMappings[1].setMethodOmissions(new String[]{"doGet"});
        
        expectedMappings[2] = new ConstraintMapping();
        expectedMappings[2].setConstraint(expectedConstraint2);
        expectedMappings[2].setPathSpec("/foo/*");
        expectedMappings[2].setMethod("doGet");
        expectedMappings[3] = new ConstraintMapping();
        expectedMappings[3].setConstraint(expectedConstraint2);
        expectedMappings[3].setPathSpec("*.foo");
        expectedMappings[3].setMethod("doGet");
        
        AnnotationConfiguration config = new AnnotationConfiguration();
        config.postConfigure(_wac);
        compareResults (expectedMappings, ((ConstraintAware)_wac.getSecurityHandler()).getConstraintMappings());
    }

    private void compareResults (ConstraintMapping[] expectedMappings, ConstraintMapping[] actualMappings)
    {
        assertNotNull(actualMappings);
        assertEquals(expectedMappings.length, actualMappings.length);

        for (int k=0; k < actualMappings.length; k++)
        {   
            ConstraintMapping am = actualMappings[k];
            boolean matched  = false;
          
            for (int i=0; i< expectedMappings.length && !matched; i++)
            {
                ConstraintMapping em = expectedMappings[i];
                if (em.getPathSpec().equals(am.getPathSpec()))
                {
                    if ((em.getMethod()==null && am.getMethod() == null) || em.getMethod() != null && em.getMethod().equals(am.getMethod()))
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
                fail("No expected ConstraintMapping matching method:"+am.getMethod()+" pathSpec: "+am.getPathSpec());
        }
    }
}
