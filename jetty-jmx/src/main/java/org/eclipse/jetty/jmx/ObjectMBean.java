//
//  ========================================================================
//  Copyright (c) 1995-2019 Mort Bay Consulting Pty. Ltd.
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

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import javax.management.Attribute;
import javax.management.AttributeChangeNotification;
import javax.management.AttributeList;
import javax.management.AttributeNotFoundException;
import javax.management.DynamicMBean;
import javax.management.ListenerNotFoundException;
import javax.management.MBeanAttributeInfo;
import javax.management.MBeanException;
import javax.management.MBeanInfo;
import javax.management.MBeanNotificationInfo;
import javax.management.MBeanOperationInfo;
import javax.management.MBeanRegistration;
import javax.management.MBeanServer;
import javax.management.Notification;
import javax.management.NotificationBroadcasterSupport;
import javax.management.NotificationEmitter;
import javax.management.NotificationFilter;
import javax.management.NotificationListener;
import javax.management.ObjectName;
import javax.management.ReflectionException;

import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

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
public class ObjectMBean implements DynamicMBean, NotificationEmitter, MBeanRegistration
{
    public static final String OPERATION_INVOKE = "org.eclipse.jetty.jmx.operation.invoke";
    public static final String OPERATION_ARGUMENTS = "org.eclipse.jetty.jmx.operation.arguments";
    private static final String OPERATION_RESULT = "org.eclipse.jetty.jmx.operation.result";
    private static final Logger LOG = Log.getLogger(ObjectMBean.class);

    private final AtomicLong _notificationSequence = new AtomicLong();
    private final NotificationBroadcasterSupport _notificationSupport = new NotificationBroadcasterSupport();
    protected final Object _managed;
    private MetaData _metaData;
    private MBeanContainer _mbeanContainer;
    private ObjectName _objectName;

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

    @Override
    public void addNotificationListener(javax.management.NotificationListener listener, NotificationFilter filter, Object handback) throws IllegalArgumentException
    {
        _notificationSupport.addNotificationListener(listener, filter, handback);
    }

    @Override
    public void removeNotificationListener(javax.management.NotificationListener listener) throws ListenerNotFoundException
    {
        _notificationSupport.removeNotificationListener(listener);
    }

    @Override
    public void removeNotificationListener(javax.management.NotificationListener listener, NotificationFilter filter, Object handback) throws ListenerNotFoundException
    {
        _notificationSupport.removeNotificationListener(listener, filter, handback);
    }

    @Override
    public MBeanNotificationInfo[] getNotificationInfo()
    {
        return metaData().getMBeanInfo().getNotifications();
    }

    public void emitNotification(Notification notification)
    {
        _mbeanContainer.emitNotification(getObjectName(), notification);
        _notificationSupport.sendNotification(notification);
    }

    @Override
    public ObjectName preRegister(MBeanServer server, ObjectName objectName)
    {
        _objectName = objectName;
        return objectName;
    }

    @Override
    public void postRegister(Boolean registrationDone)
    {
        if (registrationDone == Boolean.FALSE)
            _objectName = null;
    }

    @Override
    public void preDeregister()
    {
        _objectName = null;
    }

    @Override
    public void postDeregister()
    {
    }

    /**
     * <p>Allows to customize the ObjectName of this MBean.</p>
     *
     * @return a custom ObjectName, or null to indicate to {@link MBeanContainer} to create a default ObjectName
     */
    public ObjectName getObjectName()
    {
        return _objectName;
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

    /**
     * @param o the object to wrap as MBean
     * @return a new instance of an MBean for the object or null if the MBean cannot be created
     * @deprecated Use {@link MBeanContainer#mbeanFor(Object)} instead
     */
    @Deprecated
    public static Object mbeanFor(Object o)
    {
        return MBeanContainer.mbeanFor(null, o);
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
                    LOG.debug(x);
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
                    LOG.debug(x);
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

    void notifySetAttribute(MBeanAttributeInfo attributeInfo, Object oldValue, Object newValue)
    {
        AttributeChangeNotification notification = new AttributeChangeNotification(
                getObjectName(),
                _notificationSequence.incrementAndGet(),
                System.currentTimeMillis(),
                null,
                attributeInfo.getName(),
                attributeInfo.getType(),
                oldValue,
                newValue
        );
        Map<String, Object> userData = new HashMap<>();
        userData.put(ObjectMBean.class.getName(), this);
        userData.put(MBeanAttributeInfo.class.getName(), attributeInfo);
        notification.setUserData(userData);
        emitNotification(notification);
    }

    void notifyInvoke(MBeanOperationInfo operationInfo, Object[] arguments, Object result)
    {
        Notification notification = new Notification(OPERATION_INVOKE, getObjectName(), _notificationSequence.incrementAndGet());
        Map<String, Object> userData = new HashMap<>();
        userData.put(ObjectMBean.class.getName(), this);
        userData.put(MBeanOperationInfo.class.getName(), operationInfo);
        userData.put(OPERATION_ARGUMENTS, arguments);
        userData.put(OPERATION_RESULT, result);
        notification.setUserData(userData);
        emitNotification(notification);
    }

    @Override
    public String toString()
    {
        return String.format("%s@%x[%s]", getClass().getSimpleName(), hashCode(), getManagedObject());
    }

    public static class LoggingListener implements NotificationListener
    {
        private final Logger logger;

        public LoggingListener()
        {
            this(LoggingListener.class.getName());
        }

        public LoggingListener(String loggerName)
        {
            logger = Log.getLogger(loggerName);
        }

        @Override
        public void handleNotification(Notification notification, Object handback)
        {
            switch (notification.getType())
            {
                case AttributeChangeNotification.ATTRIBUTE_CHANGE:
                {
                    AttributeChangeNotification changeNotification = (AttributeChangeNotification)notification;
                    logger.info("JMX setAttribute '{}' on {}, value: {} -> {}",
                            changeNotification.getAttributeName(),
                            notification.getSource(),
                            formatMaybeArray(changeNotification.getOldValue()),
                            formatMaybeArray(changeNotification.getNewValue()));
                    break;
                }
                case OPERATION_INVOKE:
                {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> userData = (Map<String, Object>)notification.getUserData();
                    MBeanOperationInfo operationInfo = (MBeanOperationInfo)userData.get(MBeanOperationInfo.class.getName());
                    Object[] arguments = (Object[])userData.get(OPERATION_ARGUMENTS);
                    Object result = userData.get(OPERATION_RESULT);
                    logger.info("JMX invoke operation '{}' on {}, arguments={}, result={}",
                            operationInfo.getName(),
                            notification.getSource(),
                            Arrays.toString(arguments),
                            formatMaybeArray(result));
                    break;
                }
            }
        }

        private Object formatMaybeArray(Object result)
        {
            if (result != null && result.getClass().isArray())
                result = Arrays.toString((Object[])result);
            return result;
        }
    }
}
