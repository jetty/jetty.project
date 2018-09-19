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

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

import javax.management.*;
import javax.management.modelmbean.ModelMBean;

import org.eclipse.jetty.util.Loader;
import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.eclipse.jetty.util.annotation.ManagedOperation;
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
    private static final Container ROOT = new ContainerLifeCycle();
    private static final Class<?>[] OBJ_ARG = new Class[]{Object.class};

    private final MBeanServer _mbeanServer;
    private final ConcurrentMap<Class,Constructor<?>> _mbeanFor = new ConcurrentHashMap<>();
    private final ConcurrentMap<Object, Container> _beans = new ConcurrentHashMap<>();
    private final ConcurrentMap<Object, ObjectName> _mbeans = new ConcurrentHashMap<>();
    private String _domain = null;


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
        if (o==null)
            return null;
        
        Class<?> oClass = o.getClass();

        Constructor<?> constructor = _mbeanFor.computeIfAbsent(oClass,c->
        {
            while (true)
            {
                try
                {
                    String pName = c.getPackage().getName();
                    String cName = c.getName().substring(pName.length() + 1);
                    String mName = pName + ".jmx." + cName + "MBean";

                    Class<?> mClass;
                    try
                    {
                        // Look for an MBean class from the same loader that loaded the original class
                        mClass = (Object.class.equals(c))?c = ObjectMBean.class:Loader.loadClass(c, mName);
                    }
                    catch (ClassNotFoundException e)
                    {
                        // Not found, so if not the same as the thread context loader, try that.
                        if (Thread.currentThread().getContextClassLoader() == c.getClassLoader())
                            throw e;
                        LOG.ignore(e);
                        mClass = Loader.loadClass(c, mName);
                    }

                    if (LOG.isDebugEnabled())
                        LOG.debug("ObjectMBean: mbeanFor {} mClass={}", o, mClass);

                    return (ModelMBean.class.isAssignableFrom(mClass))
                        ?mClass.getDeclaredConstructor()
                        :mClass.getConstructor(OBJ_ARG);
                }
                catch (ClassNotFoundException | NoSuchMethodException e)
                {
                    LOG.debug(e.toString());
                    LOG.ignore(e);
                }

                c = c.getSuperclass();
                if (c==null)
                    return null;
            }
        });

        if (constructor==null)
            return null;

        try
        {
            Object mbean;
            if (constructor.getParameterCount()==0)
            {
                mbean = constructor.newInstance();
                ((ModelMBean)mbean).setManagedResource(o, "objectReference");
            }
            else
            {
                mbean = constructor.newInstance(o);
            }

            if (mbean instanceof ObjectMBean)
                ((ObjectMBean)mbean).setMBeanContainer(this);

            if (LOG.isDebugEnabled())
                LOG.debug("mbeanFor {} is {}", o, mbean);

            return mbean;
        }
        catch(Throwable t)
        {
            LOG.warn(t);
        }

        return null;
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


    MBeanInfo getMBeanInfo(ObjectMBean bean)
    {
        String desc = null;
        List<MBeanAttributeInfo> attributes = new ArrayList<>();
        List<MBeanOperationInfo> operations = new ArrayList<>();

        // Find list of classes that can influence the mbean
        Object managed = bean.getManagedObject();
        Class<?> o_class = managed.getClass();
        List<Class<?>> influences = new ArrayList<>();
        influences.add(bean.getClass()); // always add MBean itself
        influences = findInfluences(influences, managed.getClass());

        if (LOG.isDebugEnabled())
            LOG.debug("Influence Count: {}", influences.size());

        // Process Type Annotations
        ManagedObject primary = o_class.getAnnotation(ManagedObject.class);

        if (primary != null)
        {
            desc = primary.value();
        }
        else
        {
            if (LOG.isDebugEnabled())
                LOG.debug("No @ManagedObject declared on {}", managed.getClass());
        }

        // For each influence
        for (Class<?> oClass : influences)
        {
            ManagedObject typeAnnotation = oClass.getAnnotation(ManagedObject.class);

            if (LOG.isDebugEnabled())
                LOG.debug("Influenced by: " + oClass.getCanonicalName());

            if (typeAnnotation == null)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("Annotations not found for: {}", oClass.getCanonicalName());
                continue;
            }

            // Process Method Annotations

            for (Method method : oClass.getDeclaredMethods())
            {
                ManagedAttribute methodAttributeAnnotation = method.getAnnotation(ManagedAttribute.class);

                if (methodAttributeAnnotation != null)
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug("Attribute Annotation found for: {}", method.getName());
                    MBeanAttributeInfo mai = bean.defineAttribute(method, methodAttributeAnnotation);
                    if (mai != null)
                        attributes.add(mai);
                }

                ManagedOperation methodOperationAnnotation = method.getAnnotation(ManagedOperation.class);

                if (methodOperationAnnotation != null)
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug("Method Annotation found for: {}", method.getName());
                    MBeanOperationInfo oi = bean.defineOperation(method, methodOperationAnnotation);
                    if (oi != null)
                        operations.add(oi);
                }
            }
        }

        return new MBeanInfo(o_class.getName(),
            desc,
            attributes.toArray(new MBeanAttributeInfo[attributes.size()]),
            new MBeanConstructorInfo[0],
            operations.toArray(new MBeanOperationInfo[operations.size()]),
            new MBeanNotificationInfo[0]);
    }


    private static List<Class<?>> findInfluences(List<Class<?>> influences, Class<?> aClass)
    {
        if (aClass != null)
        {
            if (!influences.contains(aClass))
            {
                // This class is a new influence
                influences.add(aClass);
            }

            // So are the super classes
            influences = findInfluences(influences, aClass.getSuperclass());

            // So are the interfaces
            Class<?>[] ifs = aClass.getInterfaces();
            for (int i = 0; ifs != null && i < ifs.length; i++)
                influences = findInfluences(influences, ifs[i]);
        }
        return influences;
    }



    @Override
    public void dump(Appendable out, String indent) throws IOException
    {
        ContainerLifeCycle.dumpObject(out,this);
        ContainerLifeCycle.dump(out, indent, _mbeans.entrySet());
    }

    @Override
    public String dump()
    {
        return ContainerLifeCycle.dump(this);
    }

    @Override
    public void destroy()
    {
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
