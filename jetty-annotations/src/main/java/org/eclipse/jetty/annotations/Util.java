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

package org.eclipse.jetty.annotations;

import java.lang.reflect.Array;

import org.eclipse.jetty.util.Loader;
import org.eclipse.jetty.util.TypeUtil;
import org.objectweb.asm.Type;

/**
 * Util
 *
 *
 */
public class Util
{ 
    private static Class[] __envEntryClassTypes = 
        new Class[] {String.class, Character.class, Integer.class, Boolean.class, Double.class, Byte.class, Short.class, Long.class, Float.class};
    

    private static String[] __envEntryTypes = 
        new String[] { Type.getDescriptor(String.class), Type.getDescriptor(Character.class), Type.getDescriptor(Integer.class), Type.getDescriptor(Boolean.class),
                       Type.getDescriptor(Double.class), Type.getDescriptor(Byte.class), Type.getDescriptor(Short.class), Type.getDescriptor(Long.class), Type.getDescriptor(Float.class)};
    
    /**
     * Check if the presented method belongs to a class that is one
     * of the classes with which a servlet container should be concerned.
     * @param m
     * @return
     */
    public static boolean isServletType (Class c)
    {    
        boolean isServlet = false;
        if (javax.servlet.Servlet.class.isAssignableFrom(c) ||
                javax.servlet.Filter.class.isAssignableFrom(c) || 
                javax.servlet.ServletContextListener.class.isAssignableFrom(c) ||
                javax.servlet.ServletContextAttributeListener.class.isAssignableFrom(c) ||
                javax.servlet.ServletRequestListener.class.isAssignableFrom(c) ||
                javax.servlet.ServletRequestAttributeListener.class.isAssignableFrom(c) ||
                javax.servlet.http.HttpSessionListener.class.isAssignableFrom(c) ||
                javax.servlet.http.HttpSessionAttributeListener.class.isAssignableFrom(c))

                isServlet=true;
        
        return isServlet;  
    }

    public static boolean isEnvEntryType (Class type)
    {
        boolean result = false;
        for (int i=0;i<__envEntryClassTypes.length && !result;i++)
        {
            result = (type.equals(__envEntryClassTypes[i]));
        }
        return result;
    }
    
    public static boolean isEnvEntryType (String desc)
    {
        boolean result = false;
        for (int i=0;i<__envEntryTypes.length && !result;i++)
        {
            result = (desc.equals(__envEntryTypes[i]));
        }
        return result;
    }
    
    public static String normalizePattern(String p)
    {
        if (p!=null && p.length()>0 && !p.startsWith("/") && !p.startsWith("*"))
            return "/"+p;
        return p;
    }
    

    
    public static Class[] convertTypes (String params)
    throws Exception
    {
        return convertTypes(Type.getArgumentTypes(params));
    }
    
    
    public static Class[] convertTypes (Type[] types)
    throws Exception
    {
        if (types==null)
            return new Class[0];
        
        Class[] classArray = new Class[types.length];
        
        for (int i=0; i<types.length; i++)
        {
            classArray[i] = convertType(types[i]);
        }
        return classArray;
    }

    public static Class convertType (Type t)
    throws Exception
    {
        if (t == null)
            return (Class)null;
        
        switch (t.getSort())
        {
            case Type.BOOLEAN:
            {
                return Boolean.TYPE;
            }
            case Type.ARRAY:
            {
                Class clazz = convertType(t.getElementType());
                return Array.newInstance(clazz, 0).getClass();
            }
            case Type.BYTE:
            {
                return Byte.TYPE;
            }
            case Type.CHAR:
            {
                return Character.TYPE;
            }
            case Type.DOUBLE:
            {
                return Double.TYPE;
            }
            case Type.FLOAT:
            {
                return Float.TYPE;
            }
            case Type.INT:
            {
                return Integer.TYPE;
            }
            case Type.LONG:
            {
                return Long.TYPE;
            }
            case Type.OBJECT:
            {
                return (Loader.loadClass(null, t.getClassName()));
            }
            case Type.SHORT:
            {
                return Short.TYPE;
            }
            case Type.VOID:
            {
                return null;
            }
            default:
                return null;
        }
        
    }
  
    public static String asCanonicalName (Type t)
    {
        if (t == null)
            return null;
        
        switch (t.getSort())
        {
            case Type.BOOLEAN:
            {
                return TypeUtil.toName(Boolean.TYPE);
            }
            case Type.ARRAY:
            {
                return  t.getElementType().getClassName();
            }
            case Type.BYTE:
            {
                return TypeUtil.toName(Byte.TYPE);
            }
            case Type.CHAR:
            {
                return TypeUtil.toName(Character.TYPE);
            }
            case Type.DOUBLE:
            {
                return TypeUtil.toName(Double.TYPE);
            }
            case Type.FLOAT:
            {
                 return TypeUtil.toName(Float.TYPE);
            }
            case Type.INT:
            {
                return TypeUtil.toName(Integer.TYPE);
            }
            case Type.LONG:
            {
                return TypeUtil.toName(Long.TYPE);
            }
            case Type.OBJECT:
            {
                return t.getClassName();
            }
            case Type.SHORT:
            {
                return TypeUtil.toName(Short.TYPE);
            }
            case Type.VOID:
            {
                return null;
            }
            default:
                return null;
        }
    }
}
