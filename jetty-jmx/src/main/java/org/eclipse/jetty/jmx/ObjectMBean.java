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

import javax.management.Attribute;
import javax.management.AttributeList;
import javax.management.AttributeNotFoundException;
import javax.management.DynamicMBean;
import javax.management.MBeanException;
import javax.management.MBeanInfo;
import javax.management.ObjectName;
import javax.management.ReflectionException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p>A dynamic MBean that can wrap an arbitrary Object instance.</p>
 * <p>The attributes and operations exposed by this bean are controlled
 * by the merge of annotations discovered in all superclasses and all
 * superinterfaces.</p>
 * <p>Given class {@code com.acme.Foo}, then {@code com.acme.jmx.FooMBean}
 * is searched; if found, it is instantiated with the {@code com.acme.Foo}
 * instance passed to the constructor.</p>
 * <p>Class {@code com.acme.jmx.FooMBean} can then override the default
 * behavior of ObjectMBean and provide a custom ObjectName, or custom
 * ObjectName properties {@code name} and {@code context}, etc.</p>
 */
public class ObjectMBean implements DynamicMBean
{
    private static final Logger LOG = LoggerFactory.getLogger(ObjectMBean.class);

    protected final Object _managed;
    private MetaData _metaData;
    private MBeanContainer _mbeanContainer;

    /**
     * Creates a new ObjectMBean wrapping the given {@code managedObject}.
     *
     * @param managedObject the object to manage
     */
    public ObjectMBean(Object managedObject)
    {
        _managed = managedObject;
    }

    /**
     * @return the managed object
     */
    public Object getManagedObject()
    {
        return _managed;
    }

    /**
     * <p>Allows to customize the ObjectName of this MBean.</p>
     *
     * @return a custom ObjectName, or null to indicate to {@link MBeanContainer} to create a default ObjectName
     */
    public ObjectName getObjectName()
    {
        return null;
    }

    /**
     * <p>Allows to customize the ObjectName property {@code context}.</p>
     * <p>When {@link MBeanContainer} creates default ObjectNames, the {@code context} property
     * is "inherited" recursively by MBeans that are children of this MBean; this allows to
     * "group" descendant MBeans so that it is clear who is the ancestor they belong to.</p>
     * <p>For example, if object A has a child component B which has children components C,
     * then AMBean can override this method to return "alpha", and then the ObjectNames will be:</p>
     * <ul>
     * <li>domain:type=a,context=alpha,id=0</li>
     * <li>domain:type=b,context=alpha,id=0</li>
     * <li>domain:type=c,context=alpha,id=0</li>
     * <li>domain:type=c,context=alpha,id=1</li>
     * </ul>
     *
     * @return a custom value for the property {@code context}
     */
    public String getObjectContextBasis()
    {
        return null;
    }

    /**
     * <p>Allows to customize the ObjectName property {@code name}.</p>
     * <p>Certain components have a natural name and returning it from this method
     * allows it to be part of the ObjectName.</p>
     *
     * @return a custom value for the property {@code name}
     */
    public String getObjectNameBasis()
    {
        return null;
    }

    protected void setMBeanContainer(MBeanContainer container)
    {
        this._mbeanContainer = container;
    }

    public MBeanContainer getMBeanContainer()
    {
        return this._mbeanContainer;
    }

    @Override
    public MBeanInfo getMBeanInfo()
    {
        return metaData().getMBeanInfo();
    }

    @Override
    public Object getAttribute(String name) throws AttributeNotFoundException, ReflectionException, MBeanException
    {
        ClassLoader prevLoader = Thread.currentThread().getContextClassLoader();
        try
        {
            return metaData().getAttribute(name, this);
        }
        finally
        {
            Thread.currentThread().setContextClassLoader(prevLoader);
        }
    }

    @Override
    public AttributeList getAttributes(String[] names)
    {
        AttributeList results = new AttributeList(names.length);
        for (String name : names)
        {
            try
            {
                results.add(new Attribute(name, getAttribute(name)));
            }
            catch (Throwable x)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("Unable to get attribute {}", name, x);
            }
        }
        return results;
    }

    @Override
    public void setAttribute(Attribute attribute) throws AttributeNotFoundException, ReflectionException, MBeanException
    {
        ClassLoader prevLoader = Thread.currentThread().getContextClassLoader();
        try
        {
            metaData().setAttribute(attribute, this);
        }
        finally
        {
            Thread.currentThread().setContextClassLoader(prevLoader);
        }
    }

    @Override
    public AttributeList setAttributes(AttributeList attributes)
    {
        AttributeList results = new AttributeList(attributes.size());
        for (Attribute attribute : attributes.asList())
        {
            try
            {
                setAttribute(attribute);
                results.add(new Attribute(attribute.getName(), getAttribute(attribute.getName())));
            }
            catch (Throwable x)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("Unable to get Attribute {}", attribute, x);
            }
        }
        return results;
    }

    @Override
    public Object invoke(String name, Object[] params, String[] signature) throws ReflectionException, MBeanException
    {
        ClassLoader prevLoader = Thread.currentThread().getContextClassLoader();
        try
        {
            return metaData().invoke(name, signature, params, this);
        }
        finally
        {
            Thread.currentThread().setContextClassLoader(prevLoader);
        }
    }

    ObjectName findObjectName(Object bean)
    {
        return _mbeanContainer.findMBean(bean);
    }

    Object findBean(ObjectName objectName)
    {
        return _mbeanContainer.findBean(objectName);
    }

    MetaData metaData()
    {
        if (_metaData == null)
            _metaData = MBeanContainer.findMetaData(_mbeanContainer, _managed.getClass());
        return _metaData;
    }
}
