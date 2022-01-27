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

package org.eclipse.jetty.util.ajax;

import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.eclipse.jetty.util.ajax.JSON.Output;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p>Converts POJOs to JSON and vice versa.</p>
 * <p>The key differences with respect to {@link JSONObjectConvertor} are:</p>
 * <ul>
 * <li>returns the actual object from Convertor.fromJSON (JSONObjectConverter returns a Map)</li>
 * <li>the getters/setters are resolved at initialization (JSONObjectConverter resolves it at runtime)</li>
 * <li>correctly sets the number fields</li>
 * </ul>
 */
public class JSONPojoConvertor implements JSON.Convertor
{
    private static final Logger LOG = LoggerFactory.getLogger(JSONPojoConvertor.class);
    private static final Map<Class<?>, NumberType> __numberTypes = new HashMap<>();
    private static final NumberType SHORT = Number::shortValue;
    private static final NumberType INTEGER = Number::intValue;
    private static final NumberType FLOAT = Number::floatValue;
    private static final NumberType LONG = Number::longValue;
    private static final NumberType DOUBLE = Number::doubleValue;

    static
    {
        __numberTypes.put(Short.class, SHORT);
        __numberTypes.put(Short.TYPE, SHORT);
        __numberTypes.put(Integer.class, INTEGER);
        __numberTypes.put(Integer.TYPE, INTEGER);
        __numberTypes.put(Long.class, LONG);
        __numberTypes.put(Long.TYPE, LONG);
        __numberTypes.put(Float.class, FLOAT);
        __numberTypes.put(Float.TYPE, FLOAT);
        __numberTypes.put(Double.class, DOUBLE);
        __numberTypes.put(Double.TYPE, DOUBLE);
    }

    public static NumberType getNumberType(Class<?> clazz)
    {
        return __numberTypes.get(clazz);
    }

    protected boolean _fromJSON;
    protected Class<?> _pojoClass;
    protected Map<String, Method> _getters = new HashMap<>();
    protected Map<String, Setter> _setters = new HashMap<>();
    protected Set<String> _excluded;

    /**
     * @param pojoClass The class to convert
     */
    public JSONPojoConvertor(Class<?> pojoClass)
    {
        this(pojoClass, null, true);
    }

    /**
     * @param pojoClass The class to convert
     * @param fromJSON If true, add a class field to the JSON
     */
    public JSONPojoConvertor(Class<?> pojoClass, boolean fromJSON)
    {
        this(pojoClass, null, fromJSON);
    }

    /**
     * @param pojoClass The class to convert
     * @param excluded The fields to exclude
     */
    public JSONPojoConvertor(Class<?> pojoClass, String[] excluded)
    {
        this(pojoClass, new HashSet<>(Arrays.asList(excluded)));
    }

    /**
     * @param pojoClass The class to convert
     * @param excluded The fields to exclude
     */
    public JSONPojoConvertor(Class<?> pojoClass, Set<String> excluded)
    {
        this(pojoClass, excluded, true);
    }

    /**
     * @param pojoClass The class to convert
     * @param excluded The fields to exclude
     * @param fromJSON If true, add a class field to the JSON
     */
    public JSONPojoConvertor(Class<?> pojoClass, Set<String> excluded, boolean fromJSON)
    {
        _pojoClass = pojoClass;
        _excluded = excluded;
        _fromJSON = fromJSON;
        init();
    }

    protected void init()
    {
        Method[] methods = _pojoClass.getMethods();
        for (Method m : methods)
        {
            if (!Modifier.isStatic(m.getModifiers()) && m.getDeclaringClass() != Object.class)
            {
                String name = m.getName();
                switch (m.getParameterCount())
                {
                    case 0:
                        if (m.getReturnType() != null)
                        {
                            if (name.startsWith("is") && name.length() > 2)
                                name = name.substring(2, 3).toLowerCase(Locale.ENGLISH) + name.substring(3);
                            else if (name.startsWith("get") && name.length() > 3)
                                name = name.substring(3, 4).toLowerCase(Locale.ENGLISH) + name.substring(4);
                            else
                                break;
                            if (includeField(name, m))
                                addGetter(name, m);
                        }
                        break;
                    case 1:
                        if (name.startsWith("set") && name.length() > 3)
                        {
                            name = name.substring(3, 4).toLowerCase(Locale.ENGLISH) + name.substring(4);
                            if (includeField(name, m))
                                addSetter(name, m);
                        }
                        break;

                    default:
                        break;
                }
            }
        }
    }

    protected void addGetter(String name, Method method)
    {
        _getters.put(name, method);
    }

    protected void addSetter(String name, Method method)
    {
        _setters.put(name, new Setter(name, method));
    }

    protected Setter getSetter(String name)
    {
        return _setters.get(name);
    }

    protected boolean includeField(String name, Method m)
    {
        return _excluded == null || !_excluded.contains(name);
    }

    @Override
    public Object fromJSON(Map<String, Object> object)
    {
        try
        {
            Object obj = _pojoClass.getConstructor().newInstance();
            setProps(obj, object);
            return obj;
        }
        catch (Exception e)
        {
            // TODO return Map instead?
            throw new RuntimeException(e);
        }
    }

    private void setProps(Object obj, Map<String, Object> props)
    {
        for (Map.Entry<String, Object> entry : props.entrySet())
        {
            Setter setter = getSetter(entry.getKey());
            if (setter != null)
            {
                try
                {
                    setter.invoke(obj, entry.getValue());
                }
                catch (Exception e)
                {
                    // TODO throw exception?
                    LOG.warn("{}#{} not set from value {}={}: {}",
                        _pojoClass.getName(),
                        setter.getPropertyName(),
                        setter.getType().getName(),
                        entry.getValue(),
                        e.toString());
                }
            }
        }
    }

    @Override
    public void toJSON(Object obj, Output out)
    {
        if (_fromJSON)
            out.addClass(_pojoClass);
        for (Map.Entry<String, Method> entry : _getters.entrySet())
        {
            try
            {
                out.add(entry.getKey(), entry.getValue().invoke(obj));
            }
            catch (Exception e)
            {
                // TODO throw exception?
                LOG.warn("{}#{} excluded: {}",
                    _pojoClass.getName(),
                    entry.getKey(),
                    e.toString());
            }
        }
    }

    public static class Setter
    {
        protected String _propertyName;
        protected Method _setter;
        protected NumberType _numberType;
        protected Class<?> _type;
        protected Class<?> _componentType;

        public Setter(String propertyName, Method method)
        {
            _propertyName = propertyName;
            _setter = method;
            _type = method.getParameterTypes()[0];
            _numberType = JSONPojoConvertor.getNumberType(_type);
            if (_numberType == null && _type.isArray())
            {
                _componentType = _type.getComponentType();
                _numberType = JSONPojoConvertor.getNumberType(_componentType);
            }
        }

        public String getPropertyName()
        {
            return _propertyName;
        }

        public Method getMethod()
        {
            return _setter;
        }

        public NumberType getNumberType()
        {
            return _numberType;
        }

        public Class<?> getType()
        {
            return _type;
        }

        public Class<?> getComponentType()
        {
            return _componentType;
        }

        public boolean isPropertyNumber()
        {
            return getNumberType() != null;
        }

        public void invoke(Object obj, Object value) throws Exception
        {
            if (value == null)
                _setter.invoke(obj, value);
            else
                invokeObject(obj, value);
        }

        protected void invokeObject(Object obj, Object value) throws Exception
        {
            if (_type.isEnum())
            {
                if (value instanceof Enum)
                {
                    _setter.invoke(obj, value);
                }
                else
                {
                    @SuppressWarnings({"rawtypes", "unchecked"})
                    Class<? extends Enum> enumType = (Class<? extends Enum>)_type;
                    @SuppressWarnings("unchecked")
                    Enum<?> enumValue = Enum.valueOf(enumType, value.toString());
                    _setter.invoke(obj, enumValue);
                }
            }
            else if (isPropertyNumber() && value instanceof Number)
            {
                _setter.invoke(obj, _numberType.getActualValue((Number)value));
            }
            else if (Character.TYPE.equals(_type) || Character.class.equals(_type))
            {
                _setter.invoke(obj, String.valueOf(value).charAt(0));
            }
            else if (_componentType != null && value.getClass().isArray())
            {
                if (_numberType == null)
                {
                    int len = Array.getLength(value);
                    Object array = Array.newInstance(getComponentType(), len);
                    try
                    {
                        System.arraycopy(value, 0, array, 0, len);
                        _setter.invoke(obj, array);
                    }
                    catch (Exception e)
                    {
                        // Unusual array with multiple types.
                        LOG.trace("IGNORED", e);
                        _setter.invoke(obj, value);
                    }
                }
                else
                {
                    Object[] old = (Object[])value;
                    Object array = Array.newInstance(getComponentType(), old.length);
                    try
                    {
                        for (int i = 0; i < old.length; i++)
                        {
                            Array.set(array, i, _numberType.getActualValue((Number)old[i]));
                        }
                        _setter.invoke(obj, array);
                    }
                    catch (Exception e)
                    {
                        // unusual array with multiple types
                        LOG.trace("IGNORED", e);
                        _setter.invoke(obj, value);
                    }
                }
            }
            else
            {
                _setter.invoke(obj, value);
            }
        }
    }

    public interface NumberType
    {
        public Object getActualValue(Number number);
    }
}
