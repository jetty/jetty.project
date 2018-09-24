//
//  ========================================================================
//  Copyright (c) 1995-2018 Mort Bay Consulting Pty. Ltd.
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

import com.acme.Derived;
import com.acme.DerivedExtended;
import com.acme.DerivedManaged;

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
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

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

    @BeforeAll
    public static void beforeClass()
    {
        Logger ombLog = Log.getLogger(ObjectMBean.class);
        if (ombLog instanceof StdErrLog && !ombLog.isDebugEnabled())
            ((StdErrLog)ombLog).setHideStacks(true);
    }

    @AfterAll
    public static void afterClass()
    {
        Logger ombLog = Log.getLogger(ObjectMBean.class);
        if (ombLog instanceof StdErrLog)
            ((StdErrLog)ombLog).setHideStacks(false);
    }

    @BeforeEach
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
        assertEquals(derivedExtended, objectMBean.getManagedObject(), "Managed objects should be equal");
        assertNull(objectMBean.getObjectName(), "This method call always returns null in the actual code");
        assertNull(objectMBean.getObjectNameBasis(), "This method call always returns null in the actual code");
        assertNull(objectMBean.getObjectContextBasis(), "This method call always returns null in the actual code");
        assertEquals(container, objectMBean.getMBeanContainer(), "Mbean container should be equal");
        assertEquals("Test the mbean extended stuff", objectMBeanInfo.getDescription(), "Mbean description must be equal to : Test the mbean extended stuff");
    }

    @Test
    public void testMbeanForNullCheck()
    {
        // when
        mBean = ObjectMBean.mbeanFor(null);

        // then
        assertNull(mBean, "As we are passing null value the output should be null");
    }

    @Test
    public void testGetAttributeReflectionException() throws Exception
    {
        // given
        setUpGetAttribute("doodle4","charu");

        // when
        ReflectionException e = assertThrows(ReflectionException.class, ()-> {
            objectMBean.getAttribute("doodle4");
        });

        // then
        assertNotNull(e, "An InvocationTargetException must have occurred by now as doodle4() internally throwing exception");
    }

    private void setUpGetAttribute(String property, String value) throws Exception
    {
        Attribute attribute = new Attribute(property,value);
        objectMBean.setAttribute(attribute);
    }

    @Test
    public void testGetAttributeAttributeNotFoundException() throws Exception
    {
        // when
        AttributeNotFoundException e = assertThrows(AttributeNotFoundException.class, ()->{
            objectMBean.getAttribute("ffname");
        });

        // then
        assertNotNull(e, "An AttributeNotFoundException must have occurred by now as there is no " + "attribute with the name ffname in bean");
    }

    @Test
    public void testSetAttributeWithCorrectAttrName() throws Exception
    {
        // given
        setUpGetAttribute("fname","charu");

        // when
        value = (String)objectMBean.getAttribute("fname");

        // then
        assertEquals("charu", value, "Attribute(fname) value must be equl to charu");
    }

    @Test
    public void testSetAttributeNullCheck() throws Exception
    {
        // given
        objectMBean.setAttribute(null);

        // when
        AttributeNotFoundException e = assertThrows(AttributeNotFoundException.class, ()->{
            objectMBean.getAttribute(null);
        });

        // then
        assertNotNull(e,"An AttributeNotFoundException must have occurred by now as there is no attribute with the name null");
    }

    @Test
    public void testSetAttributeAttributeWithWrongAttrName() throws Exception
    {
        // given
        attribute = new Attribute("fnameee","charu");

        // when
        AttributeNotFoundException e = assertThrows(AttributeNotFoundException.class, ()->{
            objectMBean.setAttribute(attribute);
        });

        // then
        assertNotNull(e, "An AttributeNotFoundException must have occurred by now as there is no attribute " + "with the name ffname in bean");
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
        assertEquals("vijay", ((Attribute)(attributes.get(0))).getValue(), "Fname value must be equal to vijay");
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
        assertNotNull(mBeanDerivedManaged.getAttribute("addresses"), "Address object shouldn't be null");
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
        assertNotNull(mBeanDerivedManaged.getAttribute("aliasNames"), "Address object shouldn't be null");
        assertNull(mBeanDerivedManaged.getAttribute("derived"), "Derived object shouldn't registerd with container so its value will be null");
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
        assertEquals(EMPTY,attributes.size(),"As there is no attribute with the name fnameee, this should return empty");
    }

    private AttributeList getAttributes(String name, String value)
    {
        Attribute attribute = new Attribute(name,value);
        AttributeList attributes = new AttributeList();
        attributes.add(attribute);
        return attributes;
    }

    @Test
    public void testInvokeMBeanException() throws Exception
    {
        // given
        setMBeanInfoForInvoke();

        // when
        MBeanException e = assertThrows(MBeanException.class, ()->{
            objectMBean.invoke("doodle2",new Object[] {},new String[] {});
        });

        // then
        assertNotNull(e, "An MBeanException must have occurred by now as doodle2() in Derived bean throwing exception");
    }

    @Test
    public void testInvokeReflectionException() throws Exception
    {
        // given
        setMBeanInfoForInvoke();

        // when
        ReflectionException e = assertThrows(ReflectionException.class, ()->{
            objectMBean.invoke("doodle1",new Object[] {},new String[] {});
        });

        // then
        assertNotNull(e, "ReflectionException is null");
    }

    @Test
    public void testInvoke() throws Exception
    {
        // given
        setMBeanInfoForInvoke();

        // when
        value = (String)objectMBean.invoke("good",new Object[] {},new String[] {});

        // then
        assertEquals("not bad", value, "Method(good) invocation on objectMBean must return not bad");
    }

    @Test
    public void testInvokeNoSuchMethodException() throws Exception
    {
        // given
        setMBeanInfoForInvoke();

        // when
        // DerivedMBean contains a managed method with the name good,we must
        // call this method without any arguments
        ReflectionException e = assertThrows(ReflectionException.class, ()->{
            objectMBean.invoke("good",new Object[] {},new String[]
                    { "int aone" });
        });

        // then
        assertNotNull(e, "An ReflectionException must have occurred by now as we cannot call a methow with wrong signature");

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
