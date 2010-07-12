// ========================================================================
// Copyright (c) 2005-2009 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.jmx;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import javax.management.MBeanServer;
import javax.management.ObjectInstance;
import javax.management.ObjectName;

import org.eclipse.jetty.util.MultiMap;
import org.eclipse.jetty.util.component.AbstractLifeCycle;
import org.eclipse.jetty.util.component.Container;
import org.eclipse.jetty.util.component.Container.Relationship;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.thread.ShutdownThread;


/* ------------------------------------------------------------ */
/**
 * Container class for the MBean instances
 */
public class MBeanContainer extends AbstractLifeCycle implements Container.Listener
{
    private final MBeanServer _server;
    private final WeakHashMap<Object, ObjectName> _beans = new WeakHashMap<Object, ObjectName>();
    private final HashMap<String, Integer> _unique = new HashMap<String, Integer>();
    private final MultiMap<ObjectName> _relations = new MultiMap<ObjectName>();
    private String _domain = null;

    /* ------------------------------------------------------------ */
    /**
     * Lookup an object name by instance
     *
     * @param object instance for which object name is looked up
     * @return object name associated with specified instance, or null if not found
     */
    public synchronized ObjectName findMBean(Object object)
    {
        ObjectName bean = (ObjectName)_beans.get(object);
        return bean==null?null:bean;
    }

    /* ------------------------------------------------------------ */
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
            ObjectName bean = (ObjectName)entry.getValue();
            if (bean.equals(oname))
                return entry.getKey();
        }
        return null;
    }

    /* ------------------------------------------------------------ */
    /**
     * Constructs MBeanContainer
     *
     * @param server instance of MBeanServer for use by container
     */
    public MBeanContainer(MBeanServer server)
    {
        _server = server;

        try
        {
            start();
        }
        catch (Exception e)
        {
            Log.ignore(e);
        }
    }

    /* ------------------------------------------------------------ */
    /**
     * Retrieve instance of MBeanServer used by container
     *
     * @return instance of MBeanServer
     */
    public MBeanServer getMBeanServer()
    {
        return _server;
    }

    /* ------------------------------------------------------------ */
    /**
     * Set domain to be used to add MBeans
     *
     * @param domain domain name
     */
    public void setDomain (String domain)
    {
        _domain = domain;
    }

    /* ------------------------------------------------------------ */
    /**
     * Retrieve domain name used to add MBeans
     *
     * @return domain name
     */
    public String getDomain()
    {
        return _domain;
    }

    /* ------------------------------------------------------------ */
    /**
     * Implementation of Container.Listener interface
     *
     * @see org.eclipse.jetty.util.component.Container.Listener#add(org.eclipse.jetty.util.component.Container.Relationship)
     */
    public synchronized void add(Relationship relationship)
    {
        ObjectName parent=(ObjectName)_beans.get(relationship.getParent());
        if (parent==null)
        {
            addBean(relationship.getParent());
            parent=(ObjectName)_beans.get(relationship.getParent());
        }

        ObjectName child=(ObjectName)_beans.get(relationship.getChild());
        if (child==null)
        {
            addBean(relationship.getChild());
            child=(ObjectName)_beans.get(relationship.getChild());
        }

        if (parent!=null && child!=null)
            _relations.add(parent,relationship);
    }

    /* ------------------------------------------------------------ */
    /**
     * Implementation of Container.Listener interface
     *
     * @see org.eclipse.jetty.util.component.Container.Listener#remove(org.eclipse.jetty.util.component.Container.Relationship)
     */
    public synchronized void remove(Relationship relationship)
    {
        ObjectName parent=(ObjectName)_beans.get(relationship.getParent());
        ObjectName child=(ObjectName)_beans.get(relationship.getChild());
        if (parent!=null && child!=null)
            _relations.removeValue(parent,relationship);
    }

    /* ------------------------------------------------------------ */
    /**
     * Implementation of Container.Listener interface
     *
     * @see org.eclipse.jetty.util.component.Container.Listener#removeBean(java.lang.Object)
     */
    public synchronized void removeBean(Object obj)
    {
        ObjectName bean=(ObjectName)_beans.remove(obj);

        if (bean!=null)
        {
            List<Relationship> beanRelations = _relations.getValues(bean);
            if (beanRelations!=null && beanRelations.size()>0)
            {
                Log.debug("Unregister {}", beanRelations);
                List<Relationship> removeList = new ArrayList<Relationship>(beanRelations);
                for (Relationship relation : removeList)
                {
                    relation.getContainer().update(relation.getParent(),relation.getChild(),null,relation.getRelationship(),true);
                }
            }

            try
            {
                _server.unregisterMBean(bean);
                Log.debug("Unregistered {}", bean);
            }
            catch (javax.management.InstanceNotFoundException e)
            {
                Log.ignore(e);
            }
            catch (Exception e)
            {
                Log.warn(e);
            }
        }
    }

    /* ------------------------------------------------------------ */
    /**
     * Implementation of Container.Listener interface
     *
     * @see org.eclipse.jetty.util.component.Container.Listener#addBean(java.lang.Object)
     */
    public synchronized void addBean(Object obj)
    {
        try
        {
            if (obj == null || _beans.containsKey(obj))
                return;

            Object mbean = ObjectMBean.mbeanFor(obj);
            if (mbean == null)
                return;

            ObjectName oname = null;
            if (mbean instanceof ObjectMBean)
            {
                ((ObjectMBean) mbean).setMBeanContainer(this);
                oname = ((ObjectMBean)mbean).getObjectName();
            }

            //no override mbean object name, so make a generic one
            if (oname == null)
            {
                String type=obj.getClass().getName().toLowerCase();
                int dot = type.lastIndexOf('.');
                if (dot >= 0)
                    type = type.substring(dot + 1);

                String name=null;
                if (mbean instanceof ObjectMBean)
                {
                    name = ((ObjectMBean)mbean).getObjectNameBasis();
                    if (name!=null)
                    {
                        name=name.replace('\\','/');
                        if (name.endsWith("/"))
                            name=name.substring(0,name.length()-1);

                        int slash=name.lastIndexOf('/',name.length()-1);
                        if (slash>0)
                            name=name.substring(slash+1);
                        dot=name.lastIndexOf('.');
                        if (dot>0)
                            name=name.substring(0,dot);

                        name=name.replace(':','_').replace('*','_').replace('?','_').replace('=','_').replace(',','_').replace(' ','_');
                    }
                }

                String basis=(name!=null&&name.length()>1)?("type="+type+",name="+name):("type="+type);

                Integer count = (Integer) _unique.get(basis);
                count = count == null ? 0 : 1 + count;
                _unique.put(basis, count);

                //if no explicit domain, create one
                String domain = _domain;
                if (domain==null)
                    domain = obj.getClass().getPackage().getName();

                oname = ObjectName.getInstance(domain+":"+basis+",id="+count);
            }

            ObjectInstance oinstance = _server.registerMBean(mbean, oname);
            Log.debug("Registered {}" , oinstance.getObjectName());
            _beans.put(obj, oinstance.getObjectName());

        }
        catch (Exception e)
        {
            Log.warn("bean: "+obj,e);
        }
    }

    /* ------------------------------------------------------------ */
    /**
     * Perform actions needed to start lifecycle
     *
     * @see org.eclipse.jetty.util.component.AbstractLifeCycle#doStart()
     */
    public void doStart()
    {
        ShutdownThread.register(this);
    }

    /* ------------------------------------------------------------ */
    /**
     * Perform actions needed to stop lifecycle
     *
     * @see org.eclipse.jetty.util.component.AbstractLifeCycle#doStop()
     */
    public void doStop()
    {
        Set<Object> removeSet = new HashSet<Object>(_beans.keySet());
        for (Object removeObj : removeSet)
        {
            removeBean(removeObj);
        }
    }
}
