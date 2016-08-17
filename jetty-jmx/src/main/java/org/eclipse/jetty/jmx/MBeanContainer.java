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

import java.io.IOException;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

import javax.management.InstanceNotFoundException;
import javax.management.MBeanRegistrationException;
import javax.management.MBeanServer;
import javax.management.ObjectName;

import org.eclipse.jetty.util.annotation.ManagedObject;
import org.eclipse.jetty.util.component.Container;
import org.eclipse.jetty.util.component.ContainerLifeCycle;
import org.eclipse.jetty.util.component.Destroyable;
import org.eclipse.jetty.util.component.Dumpable;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

/**
 * Container class for the MBean instances
 */
@ManagedObject("The component that registers beans as MBeans")
public class MBeanContainer implements Container.InheritedListener, Dumpable, Destroyable
{
    private final static Logger LOG = Log.getLogger(MBeanContainer.class.getName());
    private final static ConcurrentMap<String, AtomicInteger> __unique = new ConcurrentHashMap<>();

    public static void resetUnique()
    {
        __unique.clear();
    }

    private final MBeanServer _mbeanServer;
    private final Map<Object, ObjectName> _beans = new ConcurrentHashMap<>();
    private String _domain = null;

    /**
     * Lookup an object name by instance
     *
     * @param object instance for which object name is looked up
     * @return object name associated with specified instance, or null if not found
     */
    public ObjectName findMBean(Object object)
    {
        return _beans.get(object);
    }

    /**
     * Lookup an instance by object name
     *
     * @param objectName object name of instance
     * @return instance associated with specified object name, or null if not found
     */
    public Object findBean(ObjectName objectName)
    {
        for (Map.Entry<Object, ObjectName> entry : _beans.entrySet())
        {
            if (entry.getValue().equals(objectName))
                return entry.getKey();
        }
        return null;
    }

    /**
     * Constructs MBeanContainer
     *
     * @param server instance of MBeanServer for use by container
     */
    public MBeanContainer(MBeanServer server)
    {
        _mbeanServer = server;
    }

    /**
     * Retrieve instance of MBeanServer used by container
     *
     * @return instance of MBeanServer
     */
    public MBeanServer getMBeanServer()
    {
        return _mbeanServer;
    }

    /**
     * Set domain to be used to add MBeans
     *
     * @param domain domain name
     */
    public void setDomain(String domain)
    {
        _domain = domain;
    }

    /**
     * Retrieve domain name used to add MBeans
     *
     * @return domain name
     */
    public String getDomain()
    {
        return _domain;
    }


    @Override
    public void beanAdded(Container parent, Object obj)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("beanAdded {}->{}", parent, obj);

        // Is there an object name for the parent ?
        ObjectName parentObjectName = null;
        if (parent != null)
        {
            parentObjectName = findMBean(parent);
            if (parentObjectName == null)
            {
                // Create the parent bean.
                beanAdded(null, parent);
                parentObjectName = findMBean(parent);
            }
        }

        // Does the mbean already exist ?
        if (obj == null || _beans.containsKey(obj))
            return;

        try
        {
            // Create an MBean for the object.
            Object mbean = ObjectMBean.mbeanFor(obj);
            if (mbean == null)
                return;

            ObjectName objectName = null;
            if (mbean instanceof ObjectMBean)
            {
                ((ObjectMBean)mbean).setMBeanContainer(this);
                objectName = ((ObjectMBean)mbean).getObjectName();
            }

            // No override of the mbean's ObjectName, so make a generic one.
            if (objectName == null)
            {
                // If no explicit domain, create one.
                String domain = _domain;
                if (domain == null)
                    domain = obj.getClass().getPackage().getName();

                String type = obj.getClass().getName().toLowerCase(Locale.ENGLISH);
                int dot = type.lastIndexOf('.');
                if (dot >= 0)
                    type = type.substring(dot + 1);

                StringBuilder buf = new StringBuilder();

                String context = (mbean instanceof ObjectMBean) ? makeName(((ObjectMBean)mbean).getObjectContextBasis()) : null;
                if (context == null && parentObjectName != null)
                    context = parentObjectName.getKeyProperty("context");

                if (context != null && context.length() > 1)
                    buf.append("context=").append(context).append(",");

                buf.append("type=").append(type);

                String name = (mbean instanceof ObjectMBean) ? makeName(((ObjectMBean)mbean).getObjectNameBasis()) : context;
                if (name != null && name.length() > 1)
                    buf.append(",").append("name=").append(name);

                String basis = buf.toString();

                AtomicInteger count = __unique.get(basis);
                if (count == null)
                {
                    count = new AtomicInteger();
                    AtomicInteger existing = __unique.putIfAbsent(basis, count);
                    if (existing != null)
                        count = existing;
                }

                objectName = ObjectName.getInstance(domain + ":" + basis + ",id=" + count.getAndIncrement());
            }

            _mbeanServer.registerMBean(mbean, objectName);
            if (LOG.isDebugEnabled())
                LOG.debug("Registered {}", objectName);

            _beans.put(obj, objectName);
        }
        catch (Throwable x)
        {
            LOG.warn("bean: " + obj, x);
        }
    }

    @Override
    public void beanRemoved(Container parent, Object obj)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("beanRemoved {}", obj);

        ObjectName objectName = _beans.remove(obj);

        if (objectName != null)
            unregister(objectName);
    }

    /**
     * @param basis name to strip of special characters.
     * @return normalized name
     */
    public String makeName(String basis)
    {
        if (basis == null)
            return null;
        return basis
                .replace(':', '_')
                .replace('*', '_')
                .replace('?', '_')
                .replace('=', '_')
                .replace(',', '_')
                .replace(' ', '_');
    }

    @Override
    public void dump(Appendable out, String indent) throws IOException
    {
        ContainerLifeCycle.dumpObject(out,this);
        ContainerLifeCycle.dump(out, indent, _beans.entrySet());
    }

    @Override
    public String dump()
    {
        return ContainerLifeCycle.dump(this);
    }

    @Override
    public void destroy()
    {
        _beans.values().stream()
                .filter(objectName -> objectName != null)
                .forEach(this::unregister);
    }

    private void unregister(ObjectName objectName)
    {
        try
        {
            getMBeanServer().unregisterMBean(objectName);
            if (LOG.isDebugEnabled())
                LOG.debug("Unregistered {}", objectName);
        }
        catch (MBeanRegistrationException | InstanceNotFoundException x)
        {
            LOG.ignore(x);
        }
        catch (Throwable x)
        {
            LOG.warn(x);
        }
    }
}
