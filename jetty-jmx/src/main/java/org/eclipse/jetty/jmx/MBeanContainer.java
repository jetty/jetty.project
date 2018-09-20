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
    private final boolean _cacheOtherClassLoaders;
    private final ConcurrentMap<Class,Constructor<?>> _mbeanFor = new ConcurrentHashMap<>();
    private final ConcurrentMap<Class,MetaData> _mbeanInfo = new ConcurrentHashMap<>();
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
        this(server,true);
    }

    /**
     * Constructs MBeanContainer
     *
     * @param server instance of MBeanServer for use by container
     * @param cacheOtherClassLoaders If true,  MBeans from other classloaders (eg WebAppClassLoader) will be cached.
     *                               The cache is never flushed, so this should be false if some classloaders do not live forever.
     */
    public MBeanContainer(MBeanServer server, boolean cacheOtherClassLoaders)
    {
        _mbeanServer = server;
        _cacheOtherClassLoaders = cacheOtherClassLoaders;
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

        Constructor<?> constructor = findMBeanFor(o.getClass());

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

    private Constructor<?> findMBeanFor(Class<?> oClass)
    {
        if (oClass==null)
            return null;

        Constructor<?> constructor = _mbeanFor.get(oClass);
        if (constructor!=null)
            return constructor;

        try
        {
            String pName = oClass.getPackage().getName();
            String cName = oClass.getName().substring(pName.length() + 1);
            String mName = pName + ".jmx." + cName + "MBean";

            Class<?> mClass;
            try
            {
                // Look for an MBean class from the same loader that loaded the original class
                mClass = (Object.class.equals(oClass))?oClass = ObjectMBean.class:Loader.loadClass(oClass, mName);
            }
            catch (ClassNotFoundException e)
            {
                // Not found, so if not the same as the thread context loader, try that.
                if (Thread.currentThread().getContextClassLoader() == oClass.getClassLoader())
                    throw e;
                LOG.ignore(e);
                mClass = Loader.loadClass(oClass, mName);
            }

            constructor =  (ModelMBean.class.isAssignableFrom(mClass))
                ?mClass.getDeclaredConstructor()
                :mClass.getConstructor(OBJ_ARG);
        }
        catch (ClassNotFoundException | NoSuchMethodException e)
        {
            LOG.debug(e.toString());
            LOG.ignore(e);
        }

        if (constructor==null)
            constructor = findMBeanFor(oClass.getSuperclass());

        // Can we cache the result?
        ClassLoader ourLoader = this.getClass().getClassLoader();
        if (constructor!=null && (_cacheOtherClassLoaders || oClass.getClassLoader()==ourLoader && constructor.getDeclaringClass().getClassLoader()==ourLoader))
        {
            Constructor<?> c = _mbeanFor.putIfAbsent(oClass, constructor);
            if (c!=null)
                constructor = c;
        }

        if (LOG.isDebugEnabled())
            LOG.debug("findMBeanFor {} => {}", oClass, constructor);

        return constructor;
    }


    MBeanInfo getMBeanInfo(ObjectMBean bean)
    {
        Class<?> m_class = bean.getClass();
        Object managed = bean.getManagedObject();
        Class<?> o_class = managed.getClass();

        MetaData metadata;
        ClassLoader ourLoader = this.getClass().getClassLoader();
        if (!_cacheOtherClassLoaders || m_class.getClassLoader()!=ourLoader || o_class.getClassLoader()!=ourLoader)
            // don't cache meta data from other loaders
            metadata = new MetaData(m_class,o_class);
        else
            metadata = _mbeanInfo.computeIfAbsent(o_class,c->new MetaData(m_class,c));

        if (LOG.isDebugEnabled())
            LOG.debug("MBean meta {} -> {}",bean,metadata);

        String desc = metadata._managedObject==null?null:metadata._managedObject.value();

        List<MBeanAttributeInfo> attributes = new ArrayList<>();
        for (int i=0; i<metadata._attributes.size();i++)
        {
            MBeanAttributeInfo info = bean.defineAttribute(metadata._attributes.get(i), metadata._getters.get(i));
            if (info != null)
                attributes.add(info);
        }

        List<MBeanOperationInfo> operations = new ArrayList<>();
        for (int i=0; i<metadata._operations.size();i++)
        {
            MBeanOperationInfo info = bean.defineOperation(metadata._operations.get(i), metadata._methods.get(i));
            if (info != null)
                operations.add(info);
        }

        return new MBeanInfo(o_class.getName(),
            desc,
            attributes.toArray(new MBeanAttributeInfo[attributes.size()]),
            new MBeanConstructorInfo[0],
            operations.toArray(new MBeanOperationInfo[operations.size()]),
            new MBeanNotificationInfo[0]);
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
        _mbeanFor.clear();
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

    private static class MetaData
    {
        final ManagedObject _managedObject;
        final List<ManagedAttribute> _attributes = new ArrayList<>();
        final List<Method> _getters = new ArrayList<>();
        final List<ManagedOperation> _operations = new ArrayList<>();
        final List<Method> _methods = new ArrayList<>();

        MetaData(Class<?> mClass, Class<?> oClass)
        {
            // Find list of classes that can influence the mbean
            List<Class<?>> influences = new ArrayList<>();
            influences.add(mClass); // always add MBean itself
            influences = findInfluences(influences, oClass);

            if (LOG.isDebugEnabled())
                LOG.debug("Influence Count: {}", influences.size());

            // Process Type Annotations
            _managedObject = oClass.getAnnotation(ManagedObject.class);

            // For each influence
            for (Class<?> iClass : influences)
            {
                ManagedObject typeAnnotation = iClass.getAnnotation(ManagedObject.class);

                if (typeAnnotation == null)
                    continue;

                // Process Method Annotations
                for (Method method : iClass.getDeclaredMethods())
                {
                    ManagedAttribute methodAttributeAnnotation = method.getAnnotation(ManagedAttribute.class);
                    if (methodAttributeAnnotation != null)
                    {
                        _attributes.add(methodAttributeAnnotation);
                        _getters.add(method);
                    }

                    ManagedOperation methodOperationAnnotation = method.getAnnotation(ManagedOperation.class);
                    if (methodOperationAnnotation != null)
                    {
                        _operations.add(methodOperationAnnotation);
                        _methods.add(method);
                    }
                }
            }

            if (LOG.isDebugEnabled())
            {
                LOG.debug("MBean MetaData {} {} {}",mClass.getCanonicalName(),oClass.getCanonicalName(),_managedObject);
                for (int i=0;i<_attributes.size();i++)
                    LOG.debug("      Attribute {} {}",_attributes.get(i),_getters.get(i));
                for (int i=0;i<_operations.size();i++)
                    LOG.debug("      Operation {} {}",_operations.get(i),_methods.get(i));
            }
        }

        @Override
        public String toString()
        {
            StringBuilder b = new StringBuilder();
            b.append(_managedObject).append('[');
            for (Method m:_getters)
                b.append(m.getName()).append(',');
            b.setLength(b.length()-1);
            b.append("][");
            for (Method m:_methods)
                b.append(m.getName()).append(',');
            b.setLength(b.length()-1);
            b.append(']');

            return b.toString();
        }
    }
}
