//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
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
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

import javax.management.InstanceNotFoundException;
import javax.management.MBeanRegistrationException;
import javax.management.MBeanServer;
import javax.management.ObjectInstance;
import javax.management.ObjectName;

import org.eclipse.jetty.util.component.Container;
import org.eclipse.jetty.util.component.ContainerLifeCycle;
import org.eclipse.jetty.util.component.Dumpable;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

/**
 * Container class for the MBean instances
 */
public class MBeanContainer implements Container.InheritedListener, Dumpable
{
    private final static Logger LOG = Log.getLogger(MBeanContainer.class.getName());
    private final static ConcurrentMap<String, AtomicInteger> __unique = new ConcurrentHashMap<String, AtomicInteger>();

    public static void resetUnique()
    {
        __unique.clear();
    }
    
    private final MBeanServer _mbeanServer;
    private final WeakHashMap<Object, ObjectName> _beans = new WeakHashMap<Object, ObjectName>();
    private String _domain = null;

    /**
     * Lookup an object name by instance
     *
     * @param object instance for which object name is looked up
     * @return object name associated with specified instance, or null if not found
     */
    public synchronized ObjectName findMBean(Object object)
    {
        ObjectName bean = _beans.get(object);
        return bean == null ? null : bean;
    }

    /**
     * Lookup an instance by object name
     *
     * @param oname object name of instance
     * @return instance associated with specified object name, or null if not found
     */
    public synchronized Object findBean(ObjectName oname)
    {
        for (Map.Entry<Object, ObjectName> entry : _beans.entrySet())
        {
            ObjectName bean = entry.getValue();
            if (bean.equals(oname))
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
        LOG.debug("beanAdded {}->{}",parent,obj);
        
        // Is their an object name for the parent
        ObjectName pname=null;
        if (parent!=null)
        {
            pname=_beans.get(parent);
            if (pname==null)
            {
                // create the parent bean
                beanAdded(null,parent);
                pname=_beans.get(parent);
            }
        }
        
        // Does an mbean already exist?
        if (obj == null || _beans.containsKey(obj))
            return;
        
        try
        {
            // Create an MBean for the object
            Object mbean = ObjectMBean.mbeanFor(obj);
            if (mbean == null)
                return;

            
            ObjectName oname = null;
            if (mbean instanceof ObjectMBean)
            {
                ((ObjectMBean)mbean).setMBeanContainer(this);
                oname = ((ObjectMBean)mbean).getObjectName();
            }

            //no override mbean object name, so make a generic one
            if (oname == null)
            {      
                //if no explicit domain, create one
                String domain = _domain;
                if (domain == null)
                    domain = obj.getClass().getPackage().getName();


                String type = obj.getClass().getName().toLowerCase(Locale.ENGLISH);
                int dot = type.lastIndexOf('.');
                if (dot >= 0)
                    type = type.substring(dot + 1);


                StringBuffer buf = new StringBuffer();

                String context = (mbean instanceof ObjectMBean)?makeName(((ObjectMBean)mbean).getObjectContextBasis()):null;
                if (context==null && pname!=null)
                    context=pname.getKeyProperty("context");
                                
                if (context != null && context.length()>1)
                    buf.append("context=").append(context).append(",");
                
                buf.append("type=").append(type);

                String name = (mbean instanceof ObjectMBean)?makeName(((ObjectMBean)mbean).getObjectNameBasis()):context;
                if (name != null && name.length()>1)
                    buf.append(",").append("name=").append(name);

                String basis = buf.toString();
                
                AtomicInteger count = __unique.get(basis);
                if (count==null)
                {
                    count=__unique.putIfAbsent(basis,new AtomicInteger());
                    if (count==null)
                        count=__unique.get(basis);
                }
                
                oname = ObjectName.getInstance(domain + ":" + basis + ",id=" + count.getAndIncrement());
            }

            ObjectInstance oinstance = _mbeanServer.registerMBean(mbean, oname);
            LOG.debug("Registered {}", oinstance.getObjectName());
            _beans.put(obj, oinstance.getObjectName());

        }
        catch (Exception e)
        {
            LOG.warn("bean: " + obj, e);
        }
    }

    @Override
    public void beanRemoved(Container parent, Object obj)
    {
        LOG.debug("beanRemoved {}",obj);
        ObjectName bean = _beans.remove(obj);

        if (bean != null)
        {
            try
            {
                _mbeanServer.unregisterMBean(bean);
                LOG.debug("Unregistered {}", bean);
            }
            catch (javax.management.InstanceNotFoundException e)
            {
                LOG.ignore(e);
            }
            catch (Exception e)
            {
                LOG.warn(e);
            }
        }
    }

    /**
     * @param basis name to strip of special characters.
     * @return normalized name
     */
    public String makeName(String basis)
    {
        if (basis==null)
            return basis;
        return basis.replace(':', '_').replace('*', '_').replace('?', '_').replace('=', '_').replace(',', '_').replace(' ', '_');
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

    public void destroy()
    {
        for (ObjectName oname : _beans.values())
            if (oname!=null)
            {
                try
                {
                    _mbeanServer.unregisterMBean(oname);
                }
                catch (MBeanRegistrationException | InstanceNotFoundException e)
                {
                    LOG.warn(e);
                }
            }
    }
}
