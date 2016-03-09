//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.jmx;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Arrays;

import javax.management.Attribute;
import javax.management.AttributeList;
import javax.management.AttributeNotFoundException;
import javax.management.MBeanException;
import javax.management.MBeanInfo;
import javax.management.ReflectionException;

import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.log.StdErrLog;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import com.acme.Derived;
import com.acme.DerivedExtended;
import com.acme.DerivedManaged;

public class ObjectMBeanUtilTest
{

    private ObjectMBean objectMBean;

    private DerivedExtended derivedExtended;

    private MBeanContainer container;

    private MBeanInfo objectMBeanInfo;

    private Object mBean;

    private String value;

    private Attribute attribute;

    private AttributeList attributes;

    private ObjectMBean mBeanDerivedManaged;

    private Derived[] derivedes;

    private ArrayList<Derived> aliasNames;

    private DerivedManaged derivedManaged;

    private static final int EMPTY = 0;

    @BeforeClass
    public static void beforeClass()
    {
        Logger ombLog = Log.getLogger(ObjectMBean.class);
        if (ombLog instanceof StdErrLog && !ombLog.isDebugEnabled())
            ((StdErrLog)ombLog).setHideStacks(true);
    }

    @AfterClass
    public static void afterClass()
    {
        Logger ombLog = Log.getLogger(ObjectMBean.class);
        if (ombLog instanceof StdErrLog)
            ((StdErrLog)ombLog).setHideStacks(false);
    }

    @Before
    public void setUp()
    {
        derivedExtended = new DerivedExtended();
        objectMBean = new ObjectMBean(derivedExtended);
        container = new MBeanContainer(ManagementFactory.getPlatformMBeanServer());
        objectMBean.setMBeanContainer(container);
        objectMBeanInfo = objectMBean.getMBeanInfo();
    }

    @Test
    public void testBasicOperations()
    {
        assertEquals("Managed objects should be equal",derivedExtended,objectMBean.getManagedObject());
        assertNull("This method call always returns null in the actual code",objectMBean.getObjectName());
        assertNull("This method call always returns null in the actual code",objectMBean.getObjectNameBasis());
        assertNull("This method call always returns null in the actual code",objectMBean.getObjectContextBasis());
        assertEquals("Mbean container should be equal",container,objectMBean.getMBeanContainer());
        assertEquals("Mbean description must be equal to : Test the mbean extended stuff","Test the mbean extended stuff",objectMBeanInfo.getDescription());
    }

    @Test
    public void testMbeanForNullCheck()
    {
        // when
        mBean = ObjectMBean.mbeanFor(null);

        // then
        assertNull("As we are passing null value the output should be null",mBean);
    }

    @Test(expected = ReflectionException.class)
    public void testGetAttributeReflectionException() throws Exception
    {
        // given
        setUpGetAttribute("doodle4","charu");

        // when
        objectMBean.getAttribute("doodle4");

        // then
        fail("An InvocationTargetException must have occured by now as doodle4() internally throwing exception");
    }

    private void setUpGetAttribute(String property, String value) throws Exception
    {
        Attribute attribute = new Attribute(property,value);
        objectMBean.setAttribute(attribute);
    }

    @Test(expected = AttributeNotFoundException.class)
    public void testGetAttributeAttributeNotFoundException() throws Exception
    {
        // when
        objectMBean.getAttribute("ffname");

        // then
        fail("An AttributeNotFoundException must have occured by now as there is no " + "attribute with the name ffname in bean");
    }

    @Test
    public void testSetAttributeWithCorrectAttrName() throws Exception
    {
        // given
        setUpGetAttribute("fname","charu");

        // when
        value = (String)objectMBean.getAttribute("fname");

        // then
        assertEquals("Attribute(fname) value must be equl to charu","charu",value);
    }

    @Test(expected = AttributeNotFoundException.class)
    public void testSetAttributeNullCheck() throws Exception
    {
        // given
        objectMBean.setAttribute(null);

        // when
        objectMBean.getAttribute(null);

        // then
        fail("An AttributeNotFoundException must have occured by now as there is no attribute with the name null");
    }

    @Test(expected = AttributeNotFoundException.class)
    public void testSetAttributeAttributeWithWrongAttrName() throws Exception
    {
        // given
        attribute = new Attribute("fnameee","charu");

        // when
        objectMBean.setAttribute(attribute);

        // then
        fail("An AttributeNotFoundException must have occured by now as there is no attribute " + "with the name ffname in bean");
    }

    @Test
    public void testSetAttributesWithCorrectValues() throws Exception
    {
        // given
        attributes = getAttributes("fname","vijay");
        attributes = objectMBean.setAttributes(attributes);

        // when
        attributes = objectMBean.getAttributes(new String[]
        { "fname" });

        // then
        assertEquals("Fname value must be equal to vijay","vijay",((Attribute)(attributes.get(0))).getValue());
    }

    @Test
    public void testSetAttributesForArrayTypeAttribue() throws Exception
    {
        // given
        derivedes = getArrayTypeAttribute();

        // when
        derivedManaged.setAddresses(derivedes);
        mBeanDerivedManaged.getMBeanInfo();

        // then
        assertNotNull("Address object shouldn't be null",mBeanDerivedManaged.getAttribute("addresses"));
    }

    @Test
    public void testSetAttributesForCollectionTypeAttribue() throws Exception
    {
        // given
        aliasNames = getCollectionTypeAttribute();

        // when
        derivedManaged.setAliasNames(aliasNames);
        mBeanDerivedManaged.getMBeanInfo();

        // then
        assertNotNull("Address object shouldn't be null",mBeanDerivedManaged.getAttribute("aliasNames"));
        assertNull("Derived object shouldn't registerd with container so its value will be null",mBeanDerivedManaged.getAttribute("derived"));
    }

    private Derived[] getArrayTypeAttribute()
    {
        derivedManaged = new DerivedManaged();
        mBeanDerivedManaged = new ObjectMBean(derivedManaged);
        MBeanContainer mBeanDerivedManagedContainer = new MBeanContainer(ManagementFactory.getPlatformMBeanServer());
        mBeanDerivedManaged.setMBeanContainer(mBeanDerivedManagedContainer);
        Derived derived0 = new Derived();
        mBeanDerivedManagedContainer.beanAdded(null,derived0);
        Derived[] derivedes = new Derived[3];
        for (int i = 0; i < 3; i++)
        {
            derivedes[i] = new Derived();
        }
        derivedManaged.setAddresses(derivedes);
        mBeanDerivedManaged.getMBeanInfo();
        ArrayList<Derived> aliasNames = new ArrayList<Derived>(Arrays.asList(derivedes));
        derivedManaged.setAliasNames(aliasNames);
        return derivedes;
    }

    private ArrayList<Derived> getCollectionTypeAttribute()
    {
        ArrayList<Derived> aliasNames = new ArrayList<Derived>(Arrays.asList(getArrayTypeAttribute()));
        return aliasNames;
    }

    @Test
    public void testSetAttributesException()
    {
        // given
        attributes = getAttributes("fnameee","charu");

        // when
        attributes = objectMBean.setAttributes(attributes);

        // then
        // Original code eating the exception and returning zero size list
        assertEquals("As there is no attribute with the name fnameee, this should return empty",EMPTY,attributes.size());
    }

    private AttributeList getAttributes(String name, String value)
    {
        Attribute attribute = new Attribute(name,value);
        AttributeList attributes = new AttributeList();
        attributes.add(attribute);
        return attributes;
    }

    @Test(expected = MBeanException.class)
    public void testInvokeMBeanException() throws Exception
    {
        // given
        setMBeanInfoForInvoke();

        // when
        objectMBean.invoke("doodle2",new Object[] {},new String[] {});

        // then
        fail("An MBeanException must have occured by now as doodle2() in Derived bean throwing exception");
    }

    @Test(expected = ReflectionException.class)
    public void testInvokeReflectionException() throws Exception
    {
        // given
        setMBeanInfoForInvoke();

        // when
        objectMBean.invoke("doodle1",new Object[] {},new String[] {});

        // then
        fail("An ReflectionException must have occured by now as doodle1() has private access in Derived bean");
    }

    @Test
    public void testInvoke() throws Exception
    {
        // given
        setMBeanInfoForInvoke();

        // when
        value = (String)objectMBean.invoke("good",new Object[] {},new String[] {});

        // then
        assertEquals("Method(good) invocation on objectMBean must return not bad","not bad",value);
    }

    @Test(expected = ReflectionException.class)
    public void testInvokeNoSuchMethodException() throws Exception
    {
        // given
        setMBeanInfoForInvoke();

        // when
        // DerivedMBean contains a managed method with the name good,we must
        // call this method without any arguments
        objectMBean.invoke("good",new Object[] {},new String[]
        { "int aone" });

        // then
        fail("An ReflectionException must have occured by now as we cannot call a methow with wrong signature");
    }

    private void setMBeanInfoForInvoke()
    {
        objectMBean = (ObjectMBean)ObjectMBean.mbeanFor(derivedExtended);
        container.beanAdded(null,derivedExtended);
        objectMBean.getMBeanInfo();
    }

    @Test
    public void testToVariableName()
    {
        assertEquals("fullName",objectMBean.toVariableName("isfullName"));
    }
}
