//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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
import java.lang.reflect.Constructor;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import javax.management.InstanceNotFoundException;
import javax.management.MBeanInfo;
import javax.management.MBeanRegistrationException;
import javax.management.MBeanServer;
import javax.management.ObjectName;
import javax.management.modelmbean.ModelMBean;

import org.eclipse.jetty.util.Loader;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.annotation.ManagedAttribute;
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
    private static final Logger LOG = Log.getLogger(MBeanContainer.class.getName());
    private static final ConcurrentMap<String, AtomicInteger> __unique = new ConcurrentHashMap<>();
    private static final Container ROOT = new ContainerLifeCycle();

    private final MBeanServer _mbeanServer;
    private final boolean _useCacheForOtherClassLoaders;
    private final ConcurrentMap<Class, MetaData> _metaData = new ConcurrentHashMap<>();
    private final ConcurrentMap<Object, Container> _beans = new ConcurrentHashMap<>();
    private final ConcurrentMap<Object, ObjectName> _mbeans = new ConcurrentHashMap<>();
    private String _domain = null;

    /**
     * Constructs MBeanContainer
     *
     * @param server instance of MBeanServer for use by container
     */
    public MBeanContainer(MBeanServer server)
    {
        this(server, true);
    }

    /**
     * Constructs MBeanContainer
     *
     * @param server instance of MBeanServer for use by container
     * @param cacheOtherClassLoaders If true,  MBeans from other classloaders (eg WebAppClassLoader) will be cached.
     * The cache is never flushed, so this should be false if some classloaders do not live forever.
     */
    public MBeanContainer(MBeanServer server, boolean cacheOtherClassLoaders)
    {
        _mbeanServer = server;
        _useCacheForOtherClassLoaders = cacheOtherClassLoaders;
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

    @ManagedAttribute(value = "Whether to use the cache for MBeans loaded by other ClassLoaders", readonly = true)
    public boolean isUseCacheForOtherClassLoaders()
    {
        return _useCacheForOtherClassLoaders;
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
    @ManagedAttribute("The default ObjectName domain")
    public String getDomain()
    {
        return _domain;
    }

    /**
     * <p>Creates an ObjectMBean for the given object.</p>
     * <p>Attempts to create an ObjectMBean for the object by searching the package
     * and class name space. For example an object of the type:</p>
     * <pre>
     * class com.acme.MyClass extends com.acme.util.BaseClass implements com.acme.Iface
     * </pre>
     * <p>then this method would look for the following classes:</p>
     * <ul>
     * <li>com.acme.jmx.MyClassMBean</li>
     * <li>com.acme.util.jmx.BaseClassMBean</li>
     * <li>org.eclipse.jetty.jmx.ObjectMBean</li>
     * </ul>
     *
     * @param o The object
     * @return A new instance of an MBean for the object or null.
     */
    public Object mbeanFor(Object o)
    {
        return mbeanFor(this, o);
    }

    static Object mbeanFor(MBeanContainer container, Object o)
    {
        if (o == null)
            return null;
        Object mbean = findMetaData(container, o.getClass()).newInstance(o);
        if (mbean instanceof ObjectMBean)
            ((ObjectMBean)mbean).setMBeanContainer(container);
        if (LOG.isDebugEnabled())
        {
            LOG.debug("MBean for {} is {}", o, mbean);
            if (mbean instanceof ObjectMBean)
            {
                MBeanInfo info = ((ObjectMBean)mbean).getMBeanInfo();
                for (Object a : info.getAttributes())
                {
                    LOG.debug("  {}", a);
                }
                for (Object a : info.getOperations())
                {
                    LOG.debug("  {}", a);
                }
            }
        }
        return mbean;
    }

    static MetaData findMetaData(MBeanContainer container, Class<?> klass)
    {
        if (klass == null)
            return null;
        MetaData metaData = getMetaData(container, klass);
        if (metaData != null)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Found cached {}", metaData);
            return metaData;
        }
        return newMetaData(container, klass);
    }

    private static MetaData getMetaData(MBeanContainer container, Class<?> klass)
    {
        return container == null ? null : container._metaData.get(klass);
    }

    private static MetaData newMetaData(MBeanContainer container, Class<?> klass)
    {
        if (klass == null)
            return null;
        if (klass == Object.class)
            return new MetaData(klass, null, null, Collections.emptyList());

        List<MetaData> interfaces = Arrays.stream(klass.getInterfaces())
            .map(intf -> findMetaData(container, intf))
            .collect(Collectors.toList());
        MetaData metaData = new MetaData(klass, findConstructor(klass), findMetaData(container, klass.getSuperclass()), interfaces);

        if (container != null)
        {
            if (container.isUseCacheForOtherClassLoaders() || klass.getClassLoader() == container.getClass().getClassLoader())
            {
                MetaData existing = container._metaData.putIfAbsent(klass, metaData);
                if (existing != null)
                    metaData = existing;
                if (LOG.isDebugEnabled())
                    LOG.debug("Cached {}", metaData);
            }
        }

        return metaData;
    }

    private static Constructor<?> findConstructor(Class<?> klass)
    {
        Package pkg = klass.getPackage();
        if (pkg == null)
            return null;
        String pName = pkg.getName();
        String cName = klass.getName().substring(pName.isEmpty() ? 0 : pName.length() + 1);
        String mName = pName + ".jmx." + cName + "MBean";
        try
        {
            Class<?> mbeanClass = Loader.loadClass(klass, mName);
            Constructor<?> constructor = ModelMBean.class.isAssignableFrom(mbeanClass)
                ? mbeanClass.getConstructor()
                : mbeanClass.getConstructor(Object.class);
            if (LOG.isDebugEnabled())
                LOG.debug("Found MBean wrapper: {} for {}", mName, klass.getName());
            return constructor;
        }
        catch (Throwable x)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("MBean wrapper not found: {} for {}", mName, klass.getName());
            return null;
        }
    }

    /**
     * Lookup an object name by instance
     *
     * @param object instance for which object name is looked up
     * @return object name associated with specified instance, or null if not found
     */
    public ObjectName findMBean(Object object)
    {
        return _mbeans.get(object);
    }

    /**
     * Lookup an instance by object name
     *
     * @param objectName object name of instance
     * @return instance associated with specified object name, or null if not found
     */
    public Object findBean(ObjectName objectName)
    {
        for (Map.Entry<Object, ObjectName> entry : _mbeans.entrySet())
        {
            if (entry.getValue().equals(objectName))
                return entry.getKey();
        }
        return null;
    }

    @Override
    public void beanAdded(Container parent, Object obj)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("beanAdded {}->{}", parent, obj);

        if (obj == null)
            return;

        if (parent == null)
            parent = ROOT;

        // Is the bean already tracked ?
        if (_beans.putIfAbsent(obj, parent) != null)
            return;

        // Is there an object name for the parent ?
        ObjectName parentObjectName = null;
        if (parent != ROOT)
        {
            parentObjectName = findMBean(parent);
            if (parentObjectName == null)
            {
                // Create the parent bean.
                beanAdded(null, parent);
                parentObjectName = findMBean(parent);
            }
        }

        try
        {
            // Create an MBean for the object.
            Object mbean = mbeanFor(obj);
            if (mbean == null)
                return;

            ObjectName objectName = null;
            if (mbean instanceof ObjectMBean)
            {
                objectName = ((ObjectMBean)mbean).getObjectName();
            }

            // No override of the mbean's ObjectName, so make a generic one.
            if (objectName == null)
            {
                Class<?> klass = obj.getClass();
                while (klass.isArray())
                {
                    klass = klass.getComponentType();
                }

                // If no explicit domain, create one.
                String domain = _domain;
                if (domain == null)
                {
                    Package pkg = klass.getPackage();
                    domain = pkg == null ? "" : pkg.getName();
                }

                String type = klass.getName().toLowerCase(Locale.ENGLISH);
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

            _mbeans.put(obj, objectName);
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
            LOG.debug("beanRemoved {}->{}", parent, obj);

        if (parent == null)
            parent = ROOT;

        if (_beans.remove(obj, parent))
        {
            ObjectName objectName = _mbeans.remove(obj);
            if (objectName != null)
                unregister(objectName);
        }
    }

    /**
     * @param basis name to strip of special characters.
     * @return normalized name
     */
    public String makeName(String basis)
    {
        return StringUtil.sanitizeFileSystemName(basis);
    }

    @Override
    public void dump(Appendable out, String indent) throws IOException
    {
        Dumpable.dumpObjects(out, indent, this, _mbeans.entrySet());
    }

    @Override
    public String dump()
    {
        return Dumpable.dump(this);
    }

    @Override
    public void destroy()
    {
        _metaData.clear();
        _mbeans.values().stream()
            .filter(Objects::nonNull)
            .forEach(this::unregister);
        _mbeans.clear();
        _beans.clear();
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
