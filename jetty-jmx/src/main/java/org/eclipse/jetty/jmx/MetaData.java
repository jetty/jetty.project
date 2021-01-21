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

import java.lang.annotation.Annotation;
import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import javax.management.Attribute;
import javax.management.AttributeNotFoundException;
import javax.management.MBeanAttributeInfo;
import javax.management.MBeanConstructorInfo;
import javax.management.MBeanException;
import javax.management.MBeanInfo;
import javax.management.MBeanNotificationInfo;
import javax.management.MBeanOperationInfo;
import javax.management.MBeanParameterInfo;
import javax.management.ObjectName;
import javax.management.ReflectionException;
import javax.management.modelmbean.ModelMBean;

import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.eclipse.jetty.util.annotation.ManagedOperation;
import org.eclipse.jetty.util.annotation.Name;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

class MetaData
{
    private static final Logger LOG = Log.getLogger(MetaData.class);
    private static final MBeanAttributeInfo[] NO_ATTRIBUTES = new MBeanAttributeInfo[0];
    private static final MBeanConstructorInfo[] NO_CONSTRUCTORS = new MBeanConstructorInfo[0];
    private static final MBeanOperationInfo[] NO_OPERATIONS = new MBeanOperationInfo[0];
    private static final MBeanNotificationInfo[] NO_NOTIFICATIONS = new MBeanNotificationInfo[0];

    private final Map<String, AttributeInfo> _attributes = new HashMap<>();
    private final Map<String, OperationInfo> _operations = new HashMap<>();
    private final Class<?> _klass;
    private final MetaData _parent;
    private final List<MetaData> _interfaces;
    private final Constructor<?> _constructor;
    private final MBeanInfo _info;

    MetaData(Class<?> klass, Constructor<?> constructor, MetaData parent, List<MetaData> interfaces)
    {
        _klass = klass;
        _parent = parent;
        _interfaces = interfaces;
        _constructor = constructor;
        if (_constructor != null)
            parseMethods(klass, _constructor.getDeclaringClass());
        else
            parseMethods(klass);
        _info = buildMBeanInfo(klass);
    }

    Object newInstance(Object bean)
    {
        Object mbean;
        if (_constructor != null)
            mbean = newInstance(_constructor, bean);
        else if (_parent != null)
            mbean = _parent.newInstance(bean);
        else
            mbean = new ObjectMBean(bean);
        return mbean;
    }

    MBeanInfo getMBeanInfo()
    {
        return _info;
    }

    Object getAttribute(String name, ObjectMBean mbean) throws AttributeNotFoundException, ReflectionException, MBeanException
    {
        AttributeInfo info = findAttribute(name);
        if (info == null)
            throw new AttributeNotFoundException(name);
        return info.getAttribute(mbean);
    }

    void setAttribute(Attribute attribute, ObjectMBean mbean) throws AttributeNotFoundException, ReflectionException, MBeanException
    {
        if (attribute == null)
            return;
        String name = attribute.getName();
        AttributeInfo info = findAttribute(name);
        if (info == null)
            throw new AttributeNotFoundException(name);
        info.setAttribute(attribute.getValue(), mbean);
    }

    private AttributeInfo findAttribute(String name)
    {
        if (name == null)
            return null;

        AttributeInfo result = null;
        for (MetaData intf : _interfaces)
        {
            AttributeInfo r = intf.findAttribute(name);
            if (r != null)
                result = r;
        }

        if (_parent != null)
        {
            AttributeInfo r = _parent.findAttribute(name);
            if (r != null)
                result = r;
        }

        AttributeInfo r = _attributes.get(name);
        if (r != null)
            result = r;

        return result;
    }

    Object invoke(String name, String[] params, Object[] args, ObjectMBean mbean) throws ReflectionException, MBeanException
    {
        String signature = signature(name, params);
        OperationInfo info = findOperation(signature);
        if (info == null)
            throw new ReflectionException(new NoSuchMethodException(signature));
        return info.invoke(args, mbean);
    }

    private OperationInfo findOperation(String signature)
    {
        OperationInfo result = null;
        for (MetaData intf : _interfaces)
        {
            OperationInfo r = intf.findOperation(signature);
            if (r != null)
                result = r;
        }

        if (_parent != null)
        {
            OperationInfo r = _parent.findOperation(signature);
            if (r != null)
                result = r;
        }

        OperationInfo r = _operations.get(signature);
        if (r != null)
            result = r;

        return result;
    }

    private static Object newInstance(Constructor<?> constructor, Object bean)
    {
        try
        {
            Object mbean = constructor.getParameterCount() == 0 ? constructor.newInstance() : constructor.newInstance(bean);
            if (mbean instanceof ModelMBean)
                ((ModelMBean)mbean).setManagedResource(bean, "objectReference");
            return mbean;
        }
        catch (Throwable x)
        {
            return null;
        }
    }

    private void parseMethods(Class<?>... classes)
    {
        for (Class<?> klass : classes)
        {
            // Only work on the public method of the class, not of the hierarchy.
            for (Method method : klass.getDeclaredMethods())
            {
                if (!Modifier.isPublic(method.getModifiers()))
                    continue;
                ManagedAttribute attribute = method.getAnnotation(ManagedAttribute.class);
                if (attribute != null)
                {
                    AttributeInfo info = new AttributeInfo(attribute, method);
                    if (LOG.isDebugEnabled())
                        LOG.debug("Found attribute {} for {}: {}", info._name, klass.getName(), info);
                    _attributes.put(info._name, info);
                }
                ManagedOperation operation = method.getAnnotation(ManagedOperation.class);
                if (operation != null)
                {
                    OperationInfo info = new OperationInfo(operation, method);
                    if (LOG.isDebugEnabled())
                        LOG.debug("Found operation {} for {}: {}", info._name, klass.getName(), info);
                    _operations.put(info._name, info);
                }
            }
        }
    }

    static String toAttributeName(String methodName)
    {
        String attributeName = methodName;
        if (methodName.startsWith("get") || methodName.startsWith("set"))
            attributeName = attributeName.substring(3);
        else if (methodName.startsWith("is"))
            attributeName = attributeName.substring(2);
        return attributeName.substring(0, 1).toLowerCase(Locale.ENGLISH) + attributeName.substring(1);
    }

    private static boolean isManagedObject(Class<?> klass)
    {
        if (klass.isArray())
            klass = klass.getComponentType();
        if (klass.isPrimitive())
            return false;
        while (klass != null)
        {
            if (klass.isAnnotationPresent(ManagedObject.class))
                return true;
            klass = klass.getSuperclass();
        }
        return false;
    }

    private static String signature(String name, String[] params)
    {
        return String.format("%s(%s)", name, String.join(",", params));
    }

    private static String signature(Method method)
    {
        String signature = Arrays.stream(method.getParameterTypes())
            .map(Class::getName)
            .collect(Collectors.joining(","));
        return String.format("%s(%s)", method.getName(), signature);
    }

    private MBeanInfo buildMBeanInfo(Class<?> klass)
    {
        ManagedObject managedObject = klass.getAnnotation(ManagedObject.class);
        String description = managedObject == null ? "" : managedObject.value();

        Map<String, MBeanAttributeInfo> attributeInfos = new HashMap<>();
        collectMBeanAttributeInfos(attributeInfos);

        Map<String, MBeanOperationInfo> operationInfos = new HashMap<>();
        collectMBeanOperationInfos(operationInfos);

        MBeanInfo mbeanInfo = _parent == null ? null : _parent.getMBeanInfo();
        MBeanAttributeInfo[] attributes = attributeInfos.values().toArray(NO_ATTRIBUTES);
        MBeanConstructorInfo[] constructors = mbeanInfo == null ? NO_CONSTRUCTORS : mbeanInfo.getConstructors();
        MBeanOperationInfo[] operations = operationInfos.values().toArray(NO_OPERATIONS);
        MBeanNotificationInfo[] notifications = mbeanInfo == null ? NO_NOTIFICATIONS : mbeanInfo.getNotifications();
        return new MBeanInfo(klass.getName(), description, attributes, constructors, operations, notifications);
    }

    private void collectMBeanAttributeInfos(Map<String, MBeanAttributeInfo> attributeInfos)
    {
        // Start with interfaces, overwrite with superClass, then overwrite with local attributes.
        for (MetaData intf : _interfaces)
        {
            intf.collectMBeanAttributeInfos(attributeInfos);
        }
        if (_parent != null)
        {
            MBeanAttributeInfo[] parentAttributes = _parent.getMBeanInfo().getAttributes();
            for (MBeanAttributeInfo parentAttribute : parentAttributes)
            {
                attributeInfos.put(parentAttribute.getName(), parentAttribute);
            }
        }
        for (Map.Entry<String, AttributeInfo> entry : _attributes.entrySet())
        {
            attributeInfos.put(entry.getKey(), entry.getValue()._info);
        }
    }

    private void collectMBeanOperationInfos(Map<String, MBeanOperationInfo> operationInfos)
    {
        // Start with interfaces, overwrite with superClass, then overwrite with local operations.
        for (MetaData intf : _interfaces)
        {
            intf.collectMBeanOperationInfos(operationInfos);
        }
        if (_parent != null)
        {
            MBeanOperationInfo[] parentOperations = _parent.getMBeanInfo().getOperations();
            for (MBeanOperationInfo parentOperation : parentOperations)
            {
                String signature = signature(parentOperation.getName(), Arrays.stream(parentOperation.getSignature()).map(MBeanParameterInfo::getType).toArray(String[]::new));
                operationInfos.put(signature, parentOperation);
            }
        }
        for (Map.Entry<String, OperationInfo> entry : _operations.entrySet())
        {
            operationInfos.put(entry.getKey(), entry.getValue()._info);
        }
    }

    private static MBeanException toMBeanException(InvocationTargetException x)
    {
        Throwable cause = x.getCause();
        if (cause instanceof Exception)
            return new MBeanException((Exception)cause);
        else
            return new MBeanException(x);
    }

    @Override
    public String toString()
    {
        return String.format("%s@%x[%s, attrs=%s, opers=%s]", getClass().getSimpleName(), hashCode(),
            _klass.getName(), _attributes.keySet(), _operations.keySet());
    }

    private static class AttributeInfo
    {
        private final String _name;
        private final Method _getter;
        private final Method _setter;
        private final boolean _proxied;
        private final boolean _convert;
        private final MBeanAttributeInfo _info;

        private AttributeInfo(ManagedAttribute attribute, Method getter)
        {
            String name = attribute.name();
            if ("".equals(name))
                name = toAttributeName(getter.getName());
            _name = name;

            _getter = getter;

            boolean readOnly = attribute.readonly();
            _setter = readOnly ? null : findSetter(attribute, getter, name);

            _proxied = attribute.proxied();

            Class<?> returnType = getter.getReturnType();
            _convert = isManagedObject(returnType);
            String signature = _convert
                ? returnType.isArray()
                ? ObjectName[].class.getName()
                : ObjectName.class.getName()
                : returnType.getName();

            String description = attribute.value();
            _info = new MBeanAttributeInfo(name, signature, description, true,
                _setter != null, getter.getName().startsWith("is"));
        }

        Object getAttribute(ObjectMBean mbean) throws ReflectionException, MBeanException
        {
            try
            {
                Object target = mbean.getManagedObject();
                if (_proxied || _getter.getDeclaringClass().isInstance(mbean))
                    target = mbean;
                Object result = _getter.invoke(target);
                if (result == null)
                    return null;
                if (!_convert)
                    return result;
                if (!_getter.getReturnType().isArray())
                    return mbean.findObjectName(result);
                int length = Array.getLength(result);
                ObjectName[] names = new ObjectName[length];
                for (int i = 0; i < length; ++i)
                {
                    names[i] = mbean.findObjectName(Array.get(result, i));
                }
                return names;
            }
            catch (InvocationTargetException x)
            {
                throw toMBeanException(x);
            }
            catch (Exception x)
            {
                throw new ReflectionException(x);
            }
        }

        void setAttribute(Object value, ObjectMBean mbean) throws ReflectionException, MBeanException
        {
            if (LOG.isDebugEnabled())
                LOG.debug("setAttribute {}.{}={} {}", mbean, _info.getName(), value, _info);
            try
            {
                if (_setter == null)
                    return;
                Object target = mbean.getManagedObject();
                if (_proxied || _setter.getDeclaringClass().isInstance(mbean))
                    target = mbean;
                if (!_convert || value == null)
                {
                    _setter.invoke(target, value);
                    return;
                }
                if (!_getter.getReturnType().isArray())
                {
                    value = mbean.findBean((ObjectName)value);
                    _setter.invoke(target, value);
                    return;
                }
                ObjectName[] names = (ObjectName[])value;
                Object result = new Object[names.length];
                for (int i = 0; i < names.length; ++i)
                {
                    Array.set(result, i, mbean.findBean(names[i]));
                }
                _setter.invoke(target, result);
            }
            catch (InvocationTargetException x)
            {
                throw toMBeanException(x);
            }
            catch (Exception x)
            {
                throw new ReflectionException(x);
            }
        }

        private Method findSetter(ManagedAttribute attribute, Method getter, String name)
        {
            String setterName = attribute.setter();
            if ("".equals(setterName))
                setterName = "set" + name.substring(0, 1).toUpperCase(Locale.ENGLISH) + name.substring(1);

            Method setter = null;
            Class<?> klass = getter.getDeclaringClass();
            for (Method method : klass.getMethods())
            {
                if (method.getName().equals(setterName) && method.getParameterCount() == 1)
                {
                    if (setter != null)
                    {
                        LOG.info("Multiple setters for mbean attribute {} in {}", name, klass);
                        continue;
                    }
                    if (!getter.getReturnType().equals(method.getParameterTypes()[0]))
                    {
                        LOG.info("Getter/setter type mismatch for mbean attribute {} in {}", name, klass);
                        continue;
                    }
                    setter = method;
                }
            }

            return setter;
        }

        @Override
        public String toString()
        {
            return String.format("%s@%x[%s,proxied=%b,convert=%b,info=%s]", getClass().getSimpleName(), hashCode(),
                _name, _proxied, _convert, _info);
        }
    }

    private static class OperationInfo
    {
        private final String _name;
        private final Method _method;
        private final boolean _proxied;
        private final boolean _convert;
        private final MBeanOperationInfo _info;

        private OperationInfo(ManagedOperation operation, Method method)
        {
            _name = signature(method);

            _method = method;

            _proxied = operation.proxied();

            Class<?> returnType = method.getReturnType();
            _convert = isManagedObject(returnType);
            String returnSignature = _convert
                ? returnType.isArray()
                ? ObjectName[].class.getName()
                : ObjectName.class.getName()
                : returnType.getName();

            String impactName = operation.impact();
            int impact = MBeanOperationInfo.UNKNOWN;
            if ("ACTION".equals(impactName))
                impact = MBeanOperationInfo.ACTION;
            else if ("INFO".equals(impactName))
                impact = MBeanOperationInfo.INFO;
            else if ("ACTION_INFO".equals(impactName))
                impact = MBeanOperationInfo.ACTION_INFO;

            String description = operation.value();
            MBeanParameterInfo[] parameterInfos = parameters(method.getParameterTypes(), method.getParameterAnnotations());
            _info = new MBeanOperationInfo(method.getName(), description, parameterInfos, returnSignature, impact);
        }

        public Object invoke(Object[] args, ObjectMBean mbean) throws ReflectionException, MBeanException
        {
            if (LOG.isDebugEnabled())
                LOG.debug("invoke {}.{}({}) {}", mbean, _info.getName(), Arrays.asList(args), _info);
            try
            {
                Object target = mbean.getManagedObject();
                if (_proxied || _method.getDeclaringClass().isInstance(mbean))
                    target = mbean;
                Object result = _method.invoke(target, args);
                if (result == null)
                    return null;
                if (!_convert)
                    return result;
                if (!_method.getReturnType().isArray())
                    return mbean.findObjectName(result);
                int length = Array.getLength(result);
                ObjectName[] names = new ObjectName[length];
                for (int i = 0; i < length; ++i)
                {
                    names[i] = mbean.findObjectName(Array.get(result, i));
                }
                return names;
            }
            catch (InvocationTargetException x)
            {
                throw toMBeanException(x);
            }
            catch (Exception x)
            {
                throw new ReflectionException(x);
            }
        }

        private static MBeanParameterInfo[] parameters(Class<?>[] parameterTypes, Annotation[][] parametersAnnotations)
        {
            MBeanParameterInfo[] result = new MBeanParameterInfo[parameterTypes.length];
            for (int i = 0; i < parametersAnnotations.length; i++)
            {
                MBeanParameterInfo info = null;
                String typeName = parameterTypes[i].getName();
                Annotation[] parameterAnnotations = parametersAnnotations[i];
                for (Annotation parameterAnnotation : parameterAnnotations)
                {
                    if (parameterAnnotation instanceof Name)
                    {
                        Name name = (Name)parameterAnnotation;
                        info = result[i] = new MBeanParameterInfo(name.value(), typeName, name.description());
                        break;
                    }
                }
                if (info == null)
                    result[i] = new MBeanParameterInfo("p" + i, typeName, "");
            }
            return result;
        }

        @Override
        public String toString()
        {
            return String.format("%s@%x[%s,proxied=%b,convert=%b]", getClass().getSimpleName(), hashCode(),
                _name, _proxied, _convert);
        }
    }
}
