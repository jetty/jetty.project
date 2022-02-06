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

package org.eclipse.jetty.jmx;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Arrays;
import javax.management.Attribute;
import javax.management.AttributeList;
import javax.management.AttributeNotFoundException;
import javax.management.MBeanException;
import javax.management.MBeanInfo;
import javax.management.ReflectionException;

import com.acme.Derived;
import com.acme.DerivedExtended;
import com.acme.DerivedManaged;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class ObjectMBeanUtilTest
{
    private ObjectMBean objectMBean;
    private DerivedExtended derivedExtended;
    private MBeanContainer container;
    private MBeanInfo objectMBeanInfo;
    private Attribute attribute;
    private ObjectMBean mBeanDerivedManaged;
    private DerivedManaged derivedManaged;

    @BeforeEach
    public void setUp()
    {
        container = new MBeanContainer(ManagementFactory.getPlatformMBeanServer());
        derivedExtended = new DerivedExtended();
        objectMBean = (ObjectMBean)container.mbeanFor(derivedExtended);
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
    public void testGetAttributeMBeanException() throws Exception
    {
        Attribute attribute = new Attribute("doodle4", "charu");
        objectMBean.setAttribute(attribute);

        MBeanException e = assertThrows(MBeanException.class, () -> objectMBean.getAttribute("doodle4"));

        assertNotNull(e, "An InvocationTargetException must have occurred by now as doodle4() internally throwing exception");
    }

    @Test
    public void testGetAttributeAttributeNotFoundException()
    {
        AttributeNotFoundException e = assertThrows(AttributeNotFoundException.class, () -> objectMBean.getAttribute("ffname"));

        assertNotNull(e, "An AttributeNotFoundException must have occurred by now as there is no attribute with the name ffname in bean");
    }

    @Test
    public void testSetAttributeWithCorrectAttrName() throws Exception
    {
        Attribute attribute = new Attribute("fname", "charu");
        objectMBean.setAttribute(attribute);

        String value = (String)objectMBean.getAttribute("fname");

        assertEquals("charu", value, "Attribute(fname) value must be equal to charu");
    }

    @Test
    public void testSetAttributeNullCheck() throws Exception
    {
        objectMBean.setAttribute(null);

        AttributeNotFoundException e = assertThrows(AttributeNotFoundException.class, () -> objectMBean.getAttribute(null));

        assertNotNull(e, "An AttributeNotFoundException must have occurred by now as there is no attribute with the name null");
    }

    @Test
    public void testSetAttributeAttributeWithWrongAttrName()
    {
        attribute = new Attribute("fnameee", "charu");

        AttributeNotFoundException e = assertThrows(AttributeNotFoundException.class, () -> objectMBean.setAttribute(attribute));

        assertNotNull(e, "An AttributeNotFoundException must have occurred by now as there is no attribute " + "with the name ffname in bean");
    }

    @Test
    public void testSetAttributesWithCorrectValues()
    {
        AttributeList attributes = getAttributes("fname", "vijay");
        objectMBean.setAttributes(attributes);

        attributes = objectMBean.getAttributes(new String[]{"fname"});

        assertEquals(1, attributes.size());
        assertEquals("vijay", ((Attribute)(attributes.get(0))).getValue(), "Fname value must be equal to vijay");
    }

    @Test
    public void testSetAttributesForArrayTypeAttribute() throws Exception
    {
        Derived[] deriveds = getArrayTypeAttribute();

        derivedManaged.setAddresses(deriveds);
        mBeanDerivedManaged.getMBeanInfo();

        assertNotNull(mBeanDerivedManaged.getAttribute("addresses"), "Address object shouldn't be null");
    }

    @Test
    public void testSetAttributesForCollectionTypeAttribute() throws Exception
    {
        ArrayList<Derived> aliasNames = new ArrayList<>(Arrays.asList(getArrayTypeAttribute()));

        derivedManaged.setAliasNames(aliasNames);
        mBeanDerivedManaged.getMBeanInfo();

        assertNotNull(mBeanDerivedManaged.getAttribute("aliasNames"), "Address object shouldn't be null");
        assertNull(mBeanDerivedManaged.getAttribute("derived"), "Derived object shouldn't registered with container so its value will be null");
    }

    private Derived[] getArrayTypeAttribute()
    {
        derivedManaged = new DerivedManaged();
        mBeanDerivedManaged = new ObjectMBean(derivedManaged);
        MBeanContainer mBeanDerivedManagedContainer = new MBeanContainer(ManagementFactory.getPlatformMBeanServer());
        mBeanDerivedManaged.setMBeanContainer(mBeanDerivedManagedContainer);
        Derived derived0 = new Derived();
        mBeanDerivedManagedContainer.beanAdded(null, derived0);
        Derived[] deriveds = new Derived[3];
        for (int i = 0; i < 3; i++)
        {
            deriveds[i] = new Derived();
        }
        derivedManaged.setAddresses(deriveds);
        mBeanDerivedManaged.getMBeanInfo();
        ArrayList<Derived> aliasNames = new ArrayList<>(Arrays.asList(deriveds));
        derivedManaged.setAliasNames(aliasNames);
        return deriveds;
    }

    @Test
    public void testSetAttributesException()
    {
        AttributeList attributes = getAttributes("fnameee", "charu");

        attributes = objectMBean.setAttributes(attributes);

        // Original code eating the exception and returning zero size list
        assertEquals(0, attributes.size(), "As there is no attribute with the name fnameee, this should return empty");
    }

    private AttributeList getAttributes(String name, String value)
    {
        Attribute attribute = new Attribute(name, value);
        AttributeList attributes = new AttributeList();
        attributes.add(attribute);
        return attributes;
    }

    @Test
    public void testInvokeMBeanException()
    {
        ReflectionException e = assertThrows(ReflectionException.class, () -> objectMBean.invoke("doodle2", new Object[0], new String[0]));

        assertNotNull(e, "An ReflectionException must have occurred by now as doodle2() in Derived bean is private");
    }

    @Test
    public void testInvokeReflectionException()
    {
        MBeanException e = assertThrows(MBeanException.class, () -> objectMBean.invoke("doodle1", new Object[0], new String[0]));

        assertNotNull(e, "MBeanException is null");
    }

    @Test
    public void testInvoke() throws Exception
    {
        String value = (String)objectMBean.invoke("good", new Object[0], new String[0]);

        assertEquals("not bad", value, "Method(good) invocation on objectMBean must return not bad");
    }

    @Test
    public void testInvokeNoSuchMethodException()
    {
        // DerivedMBean contains a managed method with the name good,
        // we must call this method without any arguments.
        ReflectionException e = assertThrows(ReflectionException.class, () ->
            objectMBean.invoke("good", new Object[0], new String[]{
                "int aone"
            }));

        assertNotNull(e, "A ReflectionException must have occurred by now as we cannot call a method with wrong signature");
    }

    @Test
    public void testToAttributeName()
    {
        assertEquals("fullName", MetaData.toAttributeName("isfullName"));
    }
}
