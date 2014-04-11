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

package org.eclipse.jetty.util.ajax;

import java.lang.reflect.Array;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.eclipse.jetty.util.ajax.JSON.Output;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
/**
 * Converts POJOs to JSON and vice versa.
 * The key difference:
 *  - returns the actual object from Convertor.fromJSON (JSONObjectConverter returns a Map)
 *  - the getters/setters are resolved at initialization (JSONObjectConverter resolves it at runtime)
 *  - correctly sets the number fields
 * 
 */
public class JSONPojoConvertor implements JSON.Convertor
{
    private static final Logger LOG = Log.getLogger(JSONPojoConvertor.class);
    public static final Object[] GETTER_ARG = new Object[]{}, NULL_ARG = new Object[]{null};
    private static final Map<Class<?>, NumberType> __numberTypes = new HashMap<Class<?>, NumberType>();
    
    public static NumberType getNumberType(Class<?> clazz)
    {
        return __numberTypes.get(clazz);
    }
    
    protected boolean _fromJSON;
    protected Class<?> _pojoClass;
    protected Map<String,Method> _getters = new HashMap<String,Method>();
    protected Map<String,Setter> _setters = new HashMap<String,Setter>();
    protected Set<String> _excluded;

    /**
     * @param pojoClass The class to convert
     */
    public JSONPojoConvertor(Class<?> pojoClass)
    {
        this(pojoClass, (Set<String>)null, true);
    }

    /**
     * @param pojoClass The class to convert
     * @param excluded The fields to exclude
     */
    public JSONPojoConvertor(Class<?> pojoClass, String[] excluded)
    {
        this(pojoClass, new HashSet<String>(Arrays.asList(excluded)), true);
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

    /**
     * @param pojoClass The class to convert
     * @param fromJSON If true, add a class field to the JSON
     */
    public JSONPojoConvertor(Class<?> pojoClass, boolean fromJSON)
    {
        this(pojoClass, (Set<String>)null, fromJSON);
    }
    
    /* ------------------------------------------------------------ */
    protected void init()
    {
        Method[] methods = _pojoClass.getMethods();
        for (int i=0;i<methods.length;i++)
        {
            Method m=methods[i];
            if (!Modifier.isStatic(m.getModifiers()) && m.getDeclaringClass()!=Object.class)
            {
                String name=m.getName();
                switch(m.getParameterTypes().length)
                {
                    case 0:
                        
                        if(m.getReturnType()!=null)
                        {
                            if (name.startsWith("is") && name.length()>2)
                                name=name.substring(2,3).toLowerCase(Locale.ENGLISH)+name.substring(3);
                            else if (name.startsWith("get") && name.length()>3)
                                name=name.substring(3,4).toLowerCase(Locale.ENGLISH)+name.substring(4);
                            else 
                                break;
                            if(includeField(name, m))
                                addGetter(name, m);
                        }
                        break;
                    case 1:
                        if (name.startsWith("set") && name.length()>3)
                        {
                            name=name.substring(3,4).toLowerCase(Locale.ENGLISH)+name.substring(4);
                            if(includeField(name, m))
                                addSetter(name, m);
                        }
                        break;                
                }
            }
        }
    }
    
    /* ------------------------------------------------------------ */
    protected void addGetter(String name, Method method)
    {
        _getters.put(name, method);
    }
    
    /* ------------------------------------------------------------ */
    protected void addSetter(String name, Method method)
    {
        _setters.put(name, new Setter(name, method));
    }
    
    /* ------------------------------------------------------------ */
    protected Setter getSetter(String name)
    {
        return _setters.get(name);
    }

    /* ------------------------------------------------------------ */
    protected boolean includeField(String name, Method m)
    {
        return _excluded==null || !_excluded.contains(name);
    }
    
    /* ------------------------------------------------------------ */
    protected int getExcludedCount()
    {
        return _excluded==null ? 0 : _excluded.size();
    }

    /* ------------------------------------------------------------ */
    public Object fromJSON(Map object)
    {        
        Object obj = null;
        try
        {
            obj = _pojoClass.newInstance();
        }
        catch(Exception e)
        {
            // TODO return Map instead?
            throw new RuntimeException(e);
        }
        
        setProps(obj, object);
        return obj;
    }
    
    /* ------------------------------------------------------------ */
    public int setProps(Object obj, Map<?,?> props)
    {
        int count = 0;
        for(Iterator<?> iterator = props.entrySet().iterator(); iterator.hasNext();)
        {
            Map.Entry<?, ?> entry = (Map.Entry<?,?>) iterator.next();
            Setter setter = getSetter((String)entry.getKey());
            if(setter!=null)
            {
                try
                {
                    setter.invoke(obj, entry.getValue());                    
                    count++;
                }
                catch(Exception e)
                {
                    // TODO throw exception?
                    LOG.warn(_pojoClass.getName()+"#"+setter.getPropertyName()+" not set from "+
                            (entry.getValue().getClass().getName())+"="+entry.getValue().toString());
                    log(e);
                }
            }
        }
        return count;
    }

    /* ------------------------------------------------------------ */
    public void toJSON(Object obj, Output out)
    {
        if(_fromJSON)
            out.addClass(_pojoClass);
        for(Map.Entry<String,Method> entry : _getters.entrySet())
        {            
            try
            {
                out.add(entry.getKey(), entry.getValue().invoke(obj, GETTER_ARG));                    
            }
            catch(Exception e)
            {
                // TODO throw exception?
                LOG.warn("{} property '{}' excluded. (errors)", _pojoClass.getName(), 
                        entry.getKey());
                log(e);
            }
        }        
    }
    
    /* ------------------------------------------------------------ */
    protected void log(Throwable t)
    {
        LOG.ignore(t);
    }

    /* ------------------------------------------------------------ */
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
            _numberType = __numberTypes.get(_type);
            if(_numberType==null && _type.isArray())
            {
                _componentType = _type.getComponentType();
                _numberType = __numberTypes.get(_componentType);
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
            return _numberType!=null;
        }
        
        public void invoke(Object obj, Object value) throws IllegalArgumentException, 
        IllegalAccessException, InvocationTargetException
        {
            if(value==null)
                _setter.invoke(obj, NULL_ARG);
            else
                invokeObject(obj, value);
        }
        
        protected void invokeObject(Object obj, Object value) throws IllegalArgumentException, 
            IllegalAccessException, InvocationTargetException
        {
            
            if (_type.isEnum())
            {
                if (value instanceof Enum)
                    _setter.invoke(obj, new Object[]{value});
                else
                    _setter.invoke(obj, new Object[]{Enum.valueOf((Class<? extends Enum>)_type,value.toString())});
            }
            else if(_numberType!=null && value instanceof Number)
            {
                _setter.invoke(obj, new Object[]{_numberType.getActualValue((Number)value)});
            }
            else if (Character.TYPE.equals(_type) || Character.class.equals(_type))
            {
                _setter.invoke(obj, new Object[]{String.valueOf(value).charAt(0)});
            }
            else if(_componentType!=null && value.getClass().isArray())
            {
                if(_numberType==null)
                {
                    int len = Array.getLength(value);
                    Object array = Array.newInstance(_componentType, len);
                    try
                    {
                        System.arraycopy(value, 0, array, 0, len);
                    }
                    catch(Exception e)
                    {                        
                        // unusual array with multiple types
                        LOG.ignore(e);
                        _setter.invoke(obj, new Object[]{value});
                        return;
                    }                    
                    _setter.invoke(obj, new Object[]{array});
                }
                else
                {
                    Object[] old = (Object[])value;
                    Object array = Array.newInstance(_componentType, old.length);
                    try
                    {
                        for(int i=0; i<old.length; i++)
                            Array.set(array, i, _numberType.getActualValue((Number)old[i]));
                    }
                    catch(Exception e)
                    {                        
                        // unusual array with multiple types
                        LOG.ignore(e);
                        _setter.invoke(obj, new Object[]{value});
                        return;
                    }
                    _setter.invoke(obj, new Object[]{array});
                }
            }
            else
                _setter.invoke(obj, new Object[]{value});
        }
    }
    
    public interface NumberType
    {        
        public Object getActualValue(Number number);     
    }
    
    public static final NumberType SHORT = new NumberType()
    {
        public Object getActualValue(Number number)
        {            
            return new Short(number.shortValue());
        } 
    };

    public static final NumberType INTEGER = new NumberType()
    {
        public Object getActualValue(Number number)
        {            
            return new Integer(number.intValue());
        }
    };
    
    public static final NumberType FLOAT = new NumberType()
    {
        public Object getActualValue(Number number)
        {            
            return new Float(number.floatValue());
        }      
    };

    public static final NumberType LONG = new NumberType()
    {
        public Object getActualValue(Number number)
        {            
            return number instanceof Long ? number : new Long(number.longValue());
        }     
    };

    public static final NumberType DOUBLE = new NumberType()
    {
        public Object getActualValue(Number number)
        {            
            return number instanceof Double ? number : new Double(number.doubleValue());
        }       
    };

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
}
