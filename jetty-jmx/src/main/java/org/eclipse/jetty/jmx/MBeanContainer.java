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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import javax.management.MBeanServer;
import javax.management.ObjectInstance;
import javax.management.ObjectName;

import org.eclipse.jetty.util.component.AbstractLifeCycle;
import org.eclipse.jetty.util.component.AggregateLifeCycle;
import org.eclipse.jetty.util.component.Container;
import org.eclipse.jetty.util.component.Container.Relationship;
import org.eclipse.jetty.util.component.Dumpable;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.log.StdErrLog;
import org.eclipse.jetty.util.thread.ShutdownThread;

/**
 * Container class for the MBean instances
 */
public class MBeanContainer extends AbstractLifeCycle implements Container.Listener, Dumpable
{
    private final static Logger LOG = Log.getLogger(MBeanContainer.class.getName());
    private final static HashMap<String, Integer> __unique = new HashMap<String, Integer>();
    
    public final static void resetUnique()
    {
        synchronized (__unique)
        {
            __unique.clear();
        }
    }
    
    private final MBeanServer _server;
    private final WeakHashMap<Object, ObjectName> _beans = new WeakHashMap<Object, ObjectName>();
    private final WeakHashMap<ObjectName,List<Container.Relationship>> _relations = new WeakHashMap<ObjectName,List<Container.Relationship>>();
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
        _server = server;
    }

    /**
     * Retrieve instance of MBeanServer used by container
     *
     * @return instance of MBeanServer
     */
    public MBeanServer getMBeanServer()
    {
        return _server;
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
     * Implementation of Container.Listener interface
     *
     * @see org.eclipse.jetty.util.component.Container.Listener#add(org.eclipse.jetty.util.component.Container.Relationship)
     */
    public synchronized void add(Relationship relationship)
    {
        LOG.debug("add {}",relationship);
        ObjectName parent = _beans.get(relationship.getParent());
        if (parent == null)
        {
            addBean(relationship.getParent());
            parent = _beans.get(relationship.getParent());
        }

        ObjectName child = _beans.get(relationship.getChild());
        if (child == null)
        {
            addBean(relationship.getChild());
            child = _beans.get(relationship.getChild());
        }

        if (parent != null && child != null)
        {
            List<Container.Relationship> rels = _relations.get(parent);
            if (rels==null)
            {
                rels=new ArrayList<Container.Relationship>();
                _relations.put(parent,rels);
            }
            rels.add(relationship);
        }
    }

    /**
     * Implementation of Container.Listener interface
     *
     * @see org.eclipse.jetty.util.component.Container.Listener#remove(org.eclipse.jetty.util.component.Container.Relationship)
     */
    public synchronized void remove(Relationship relationship)
    {
        LOG.debug("remove {}",relationship);
        ObjectName parent = _beans.get(relationship.getParent());
        ObjectName child = _beans.get(relationship.getChild());

        if (parent != null && child != null)
        {
            List<Container.Relationship> rels = _relations.get(parent);
            if (rels!=null)
            {
                for (Iterator<Container.Relationship> i=rels.iterator();i.hasNext();)
                {
                    Container.Relationship r = i.next();
                    if (relationship.equals(r) || r.getChild()==null)
                        i.remove();
                }
            }
        }
    }

    /**
     * Implementation of Container.Listener interface
     *
     * @see org.eclipse.jetty.util.component.Container.Listener#removeBean(java.lang.Object)
     */
    public synchronized void removeBean(Object obj)
    {
        LOG.debug("removeBean {}",obj);
        ObjectName bean = _beans.remove(obj);

        if (bean != null)
        {
            List<Container.Relationship> beanRelations= _relations.remove(bean);
            if (beanRelations != null)
            {
                LOG.debug("Unregister {}", beanRelations);
                List<?> removeList = new ArrayList<Object>(beanRelations);
                for (Object r : removeList)
                {
                    Container.Relationship relation = (Relationship)r;
                    relation.getContainer().update(relation.getParent(), relation.getChild(), null, relation.getRelationship(), true);
                }
            }

            try
            {
                _server.unregisterMBean(bean);
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
     * Implementation of Container.Listener interface
     *
     * @see org.eclipse.jetty.util.component.Container.Listener#addBean(java.lang.Object)
     */
    public synchronized void addBean(Object obj)
    {
        LOG.debug("addBean {}",obj);
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
                ((ObjectMBean)mbean).setMBeanContainer(this);
                oname = ((ObjectMBean)mbean).getObjectName();
            }

            //no override mbean object name, so make a generic one
            if (oname == null)
            {
                String type = obj.getClass().getName().toLowerCase(Locale.ENGLISH);
                int dot = type.lastIndexOf('.');
                if (dot >= 0)
                    type = type.substring(dot + 1);

                String context = null;
                if (mbean instanceof ObjectMBean)
                {
                    context = makeName(((ObjectMBean)mbean).getObjectContextBasis());
                }

                String name = null;
                if (mbean instanceof ObjectMBean)
                {
                    name = makeName(((ObjectMBean)mbean).getObjectNameBasis());
                }

                StringBuffer buf = new StringBuffer();
                buf.append("type=").append(type);
                if (context != null && context.length()>1)
                {
                    buf.append(buf.length()>0 ? ",":"");
                    buf.append("context=").append(context);
                }
                if (name != null && name.length()>1)
                {
                    buf.append(buf.length()>0 ? ",":"");
                    buf.append("name=").append(name);
                }
                    
                String basis = buf.toString();
                Integer count;
                synchronized (__unique)
                {
                    count = __unique.get(basis);
                    count = count == null ? 0 : 1 + count;
                    __unique.put(basis, count);
                }

                //if no explicit domain, create one
                String domain = _domain;
                if (domain == null)
                    domain = obj.getClass().getPackage().getName();

                oname = ObjectName.getInstance(domain + ":" + basis + ",id=" + count);
            }

            ObjectInstance oinstance = _server.registerMBean(mbean, oname);
            LOG.debug("Registered {}", oinstance.getObjectName());
            _beans.put(obj, oinstance.getObjectName());

        }
        catch (Exception e)
        {
            LOG.warn("bean: " + obj, e);
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

    /**
     * Perform actions needed to start lifecycle
     *
     * @see org.eclipse.jetty.util.component.AbstractLifeCycle#doStart()
     */
    public void doStart()
    {
        ShutdownThread.register(this);
    }

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

    public void dump(Appendable out, String indent) throws IOException
    {
        AggregateLifeCycle.dumpObject(out,this);
        AggregateLifeCycle.dump(out, indent, _beans.entrySet());
    }

    public String dump()
    {
        return AggregateLifeCycle.dump(this);
    }
}
