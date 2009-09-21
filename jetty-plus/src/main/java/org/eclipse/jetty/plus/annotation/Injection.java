// ========================================================================
// Copyright (c) 2006-2009 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.plus.annotation;

import java.lang.reflect.Field;
import java.lang.reflect.Member;
import java.lang.reflect.Method;

import javax.naming.InitialContext;
import javax.naming.NamingException;

import org.eclipse.jetty.util.IntrospectionUtil;
import org.eclipse.jetty.util.Loader;
import org.eclipse.jetty.util.TypeUtil;
import org.eclipse.jetty.util.log.Log;

/**
 * Injection
 *
 * Represents the injection of a resource into a target (method or field).
 * The injection is performed by doing an ENC lookup using the jndi
 * name provided, and setting the object obtained on the target.
 *
 */
public class Injection
{
    private Class _targetClass;
    private String _jndiName;
    private String _mappingName;
    private Member _target;
    private String _className;
    private String _fieldName;
    private String _methodName;
    private String _paramCanonicalName;
    private String _annotationResourceType;
    
    
    public Injection ()
    {}
    

    /**
     * @return the _className
     */
    public Class getTargetClass()
    {
        return _targetClass;
    }

    
    public String getTargetClassName()
    {
        return _className;
    }

    public String getFieldName ()
    {
        return _fieldName;
    }
    
    public String getMethodName ()
    {
        return _methodName;
    }
    
    public String getParamCanonicalName ()
    {
        return _paramCanonicalName;
    }
    
    public boolean isField ()
    {
        return (_fieldName != null);
    }
    
    public boolean isMethod ()
    {
        return (_methodName != null);
    }
    
    /**
     * @return the jndiName
     */
    public String getJndiName()
    {
        return _jndiName;
    }
    /**
     * @param jndiName the jndiName to set
     */
    public void setJndiName(String jndiName)
    {
        this._jndiName = jndiName;
    }
    /**
     * @return the mappingName
     */
    public String getMappingName()
    {
        return _mappingName;
    }
    /**
     * @param mappingName the mappingName to set
     */
    public void setMappingName(String mappingName)
    {
        this._mappingName = mappingName;
    }
    
    /**
     * @return the target
     */
    public Member getTarget()
    {
        return _target;
    }
    
 
 
    /**
     * Set up an injection target that is a field
     * @param className
     * @param fieldName
     */
    public void setTarget (String className, String fieldName, String annotationResourceType)
    {
        _className = className;
        _fieldName = fieldName;
        _annotationResourceType = annotationResourceType;
    }
    
    public void setTarget (String className, String methodName, String paramCanonicalName, String annotationResourceType)
    {
        _className = className;
        _methodName = methodName;
        _paramCanonicalName = paramCanonicalName;
        _annotationResourceType = annotationResourceType;
    }
    
    
    public void setTarget (Class clazz, String targetName, Class targetType)
    {
        //first look for a javabeans style setter matching the targetName
        String setter = "set"+targetName.substring(0,1).toUpperCase()+targetName.substring(1);
        try
        {
            Log.debug("Looking for method for setter: "+setter+" with arg "+targetType);
            _target = IntrospectionUtil.findMethod(clazz, setter, new Class[] {targetType}, true, false); 
            _targetClass = clazz;
            _className = clazz.getCanonicalName();
            _methodName = targetName;
            _paramCanonicalName = targetType.getCanonicalName();
        }
        catch (NoSuchMethodException me)
        {
            //try as a field
            try
            {
                _target = IntrospectionUtil.findField(clazz, targetName, targetType, true, false);
                _targetClass = clazz;   
                _className = clazz.getCanonicalName();
                _fieldName = targetName;
            }
            catch (NoSuchFieldException fe)
            {
                throw new IllegalArgumentException("No such field or method "+targetName+" on class "+_targetClass);
            }
        }
    }

    
    /**
     * Inject a value for a Resource from JNDI into an object
     * @param injectable
     * @throws Exception
     */
    public void inject (Object injectable)
    { 
        try
        {
            if (_target == null)
                loadField();

            if (_target == null)
                loadMethod();
        }
        catch (Exception e)
        {
            throw new IllegalStateException (e);
        }

        if (_target != null)
        {
            if (_target instanceof Field)
                injectField((Field)_target, injectable);
            else
                injectMethod((Method)_target, injectable);
        }
        else
            throw new IllegalStateException ("No method or field to inject with "+getJndiName());
    }

    
    /**
     * The Resource must already exist in the ENC of this webapp.
     * @return
     * @throws Exception
     */
    public Object lookupInjectedValue ()
    throws NamingException
    {
        InitialContext context = new InitialContext();
        return context.lookup("java:comp/env/"+getJndiName());
    }
    
    

    /**
     * Inject value from jndi into a field of an instance
     * @param field
     * @param injectable
     */
    protected void injectField (Field field, Object injectable)
    {   
        if (validateInjection())
        {
            try
            {
                boolean accessibility = field.isAccessible();
                field.setAccessible(true);
                field.set(injectable, lookupInjectedValue());
                field.setAccessible(accessibility);
            }
            catch (Exception e)
            {
                Log.warn(e);
                throw new IllegalStateException("Inject failed for field "+field.getName());
            }
        }
        else
            throw new IllegalStateException ("Invalid injection for "+_className+"."+_fieldName);
    }

    /**
     * Inject value from jndi into a setter method of an instance
     * @param method
     * @param injectable
     */
    protected void injectMethod (Method method, Object injectable)
    {
        if (validateInjection())
        {
            try
            {
                boolean accessibility = method.isAccessible();
                method.setAccessible(true);
                method.invoke(injectable, new Object[] {lookupInjectedValue()});
                method.setAccessible(accessibility);
            }
            catch (Exception e)
            {
                Log.warn(e);
                throw new IllegalStateException("Inject failed for method "+method.getName());
            }
        }
        else
        throw new IllegalStateException("Invalid injection for "+_className+"."+_methodName);
    }



    protected void loadField()
    throws ClassNotFoundException, NoSuchFieldException
    {
        if (_fieldName == null || _className == null)
            return;
        
        if (_targetClass == null)
            _targetClass = Loader.loadClass(null, _className);
        
        _target = _targetClass.getDeclaredField(_fieldName);
    }
    
    
    /**
     * Load the target class and method.
     * A valid injection target method only has 1 argument.
     * @throws ClassNotFoundException
     * @throws NoSuchMethodException
     */
    protected void loadMethod ()
    throws ClassNotFoundException, NoSuchMethodException
    {
        if (_methodName == null || _className == null)
            return;

        if (_targetClass == null)
            _targetClass = Loader.loadClass(null, _className);
          
        Class arg =  TypeUtil.fromName(_paramCanonicalName);
        
        if (arg == null)
            arg = Loader.loadClass(null, _paramCanonicalName);
      
        _target = _targetClass.getDeclaredMethod(_methodName, new Class[] {arg}); 
    }
    
    
    private boolean validateInjection ()
    {
   
        //check that if the injection came from an annotation, the type specified in the annotation
        //is compatible with the field or method to inject
        //JavaEE spec sec 5.2.4
        if (_annotationResourceType != null)
        {
            if (_target == null)
                return false;
            
            try
            {
                Class<?> annotationType = TypeUtil.fromName(_annotationResourceType);
                if (annotationType == null)
                    annotationType = Loader.loadClass(null, _annotationResourceType);

                if (_target instanceof Field)
                {
                    return ((Field)_target).getType().isAssignableFrom(annotationType);
                }
                else if (_target instanceof Method)
                {
                    Class<?>[] args = ((Method)_target).getParameterTypes();
                    return args[0].isAssignableFrom(annotationType);
                }

                return false;
            }
            catch (Exception e)
            {
                Log.warn("Unable to verify injection for "+_className+"."+ (_fieldName==null?_methodName:_fieldName));
                return false;
            }
        }
        else
            return true;
    }
}
