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

package org.eclipse.jetty.util;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;

import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;


/* ------------------------------------------------------------ */
/**
 * TYPE Utilities.
 * Provides various static utiltiy methods for manipulating types and their
 * string representations.
 *
 * @since Jetty 4.1
 */
public class TypeUtil
{
    private static final Logger LOG = Log.getLogger(TypeUtil.class);
    public static int CR = '\015';
    public static int LF = '\012';

    /* ------------------------------------------------------------ */
    private static final HashMap<String, Class<?>> name2Class=new HashMap<String, Class<?>>();
    static
    {
        name2Class.put("boolean",java.lang.Boolean.TYPE);
        name2Class.put("byte",java.lang.Byte.TYPE);
        name2Class.put("char",java.lang.Character.TYPE);
        name2Class.put("double",java.lang.Double.TYPE);
        name2Class.put("float",java.lang.Float.TYPE);
        name2Class.put("int",java.lang.Integer.TYPE);
        name2Class.put("long",java.lang.Long.TYPE);
        name2Class.put("short",java.lang.Short.TYPE);
        name2Class.put("void",java.lang.Void.TYPE);

        name2Class.put("java.lang.Boolean.TYPE",java.lang.Boolean.TYPE);
        name2Class.put("java.lang.Byte.TYPE",java.lang.Byte.TYPE);
        name2Class.put("java.lang.Character.TYPE",java.lang.Character.TYPE);
        name2Class.put("java.lang.Double.TYPE",java.lang.Double.TYPE);
        name2Class.put("java.lang.Float.TYPE",java.lang.Float.TYPE);
        name2Class.put("java.lang.Integer.TYPE",java.lang.Integer.TYPE);
        name2Class.put("java.lang.Long.TYPE",java.lang.Long.TYPE);
        name2Class.put("java.lang.Short.TYPE",java.lang.Short.TYPE);
        name2Class.put("java.lang.Void.TYPE",java.lang.Void.TYPE);

        name2Class.put("java.lang.Boolean",java.lang.Boolean.class);
        name2Class.put("java.lang.Byte",java.lang.Byte.class);
        name2Class.put("java.lang.Character",java.lang.Character.class);
        name2Class.put("java.lang.Double",java.lang.Double.class);
        name2Class.put("java.lang.Float",java.lang.Float.class);
        name2Class.put("java.lang.Integer",java.lang.Integer.class);
        name2Class.put("java.lang.Long",java.lang.Long.class);
        name2Class.put("java.lang.Short",java.lang.Short.class);

        name2Class.put("Boolean",java.lang.Boolean.class);
        name2Class.put("Byte",java.lang.Byte.class);
        name2Class.put("Character",java.lang.Character.class);
        name2Class.put("Double",java.lang.Double.class);
        name2Class.put("Float",java.lang.Float.class);
        name2Class.put("Integer",java.lang.Integer.class);
        name2Class.put("Long",java.lang.Long.class);
        name2Class.put("Short",java.lang.Short.class);

        name2Class.put(null,java.lang.Void.TYPE);
        name2Class.put("string",java.lang.String.class);
        name2Class.put("String",java.lang.String.class);
        name2Class.put("java.lang.String",java.lang.String.class);
    }

    /* ------------------------------------------------------------ */
    private static final HashMap<Class<?>, String> class2Name=new HashMap<Class<?>, String>();
    static
    {
        class2Name.put(java.lang.Boolean.TYPE,"boolean");
        class2Name.put(java.lang.Byte.TYPE,"byte");
        class2Name.put(java.lang.Character.TYPE,"char");
        class2Name.put(java.lang.Double.TYPE,"double");
        class2Name.put(java.lang.Float.TYPE,"float");
        class2Name.put(java.lang.Integer.TYPE,"int");
        class2Name.put(java.lang.Long.TYPE,"long");
        class2Name.put(java.lang.Short.TYPE,"short");
        class2Name.put(java.lang.Void.TYPE,"void");

        class2Name.put(java.lang.Boolean.class,"java.lang.Boolean");
        class2Name.put(java.lang.Byte.class,"java.lang.Byte");
        class2Name.put(java.lang.Character.class,"java.lang.Character");
        class2Name.put(java.lang.Double.class,"java.lang.Double");
        class2Name.put(java.lang.Float.class,"java.lang.Float");
        class2Name.put(java.lang.Integer.class,"java.lang.Integer");
        class2Name.put(java.lang.Long.class,"java.lang.Long");
        class2Name.put(java.lang.Short.class,"java.lang.Short");

        class2Name.put(null,"void");
        class2Name.put(java.lang.String.class,"java.lang.String");
    }

    /* ------------------------------------------------------------ */
    private static final HashMap<Class<?>, Method> class2Value=new HashMap<Class<?>, Method>();
    static
    {
        try
        {
            Class<?>[] s ={java.lang.String.class};

            class2Value.put(java.lang.Boolean.TYPE,
                           java.lang.Boolean.class.getMethod("valueOf",s));
            class2Value.put(java.lang.Byte.TYPE,
                           java.lang.Byte.class.getMethod("valueOf",s));
            class2Value.put(java.lang.Double.TYPE,
                           java.lang.Double.class.getMethod("valueOf",s));
            class2Value.put(java.lang.Float.TYPE,
                           java.lang.Float.class.getMethod("valueOf",s));
            class2Value.put(java.lang.Integer.TYPE,
                           java.lang.Integer.class.getMethod("valueOf",s));
            class2Value.put(java.lang.Long.TYPE,
                           java.lang.Long.class.getMethod("valueOf",s));
            class2Value.put(java.lang.Short.TYPE,
                           java.lang.Short.class.getMethod("valueOf",s));

            class2Value.put(java.lang.Boolean.class,
                           java.lang.Boolean.class.getMethod("valueOf",s));
            class2Value.put(java.lang.Byte.class,
                           java.lang.Byte.class.getMethod("valueOf",s));
            class2Value.put(java.lang.Double.class,
                           java.lang.Double.class.getMethod("valueOf",s));
            class2Value.put(java.lang.Float.class,
                           java.lang.Float.class.getMethod("valueOf",s));
            class2Value.put(java.lang.Integer.class,
                           java.lang.Integer.class.getMethod("valueOf",s));
            class2Value.put(java.lang.Long.class,
                           java.lang.Long.class.getMethod("valueOf",s));
            class2Value.put(java.lang.Short.class,
                           java.lang.Short.class.getMethod("valueOf",s));
        }
        catch(Exception e)
        {
            throw new Error(e);
        }
    }

    /* ------------------------------------------------------------ */
    /** Array to List.
     * <p>
     * Works like {@link Arrays#asList(Object...)}, but handles null arrays.
     * @return a list backed by the array.
     */
    public static <T> List<T> asList(T[] a) 
    {
        if (a==null)
            return Collections.emptyList();
        return Arrays.asList(a);
    }
    
    /* ------------------------------------------------------------ */
    /** Class from a canonical name for a type.
     * @param name A class or type name.
     * @return A class , which may be a primitive TYPE field..
     */
    public static Class<?> fromName(String name)
    {
        return name2Class.get(name);
    }

    /* ------------------------------------------------------------ */
    /** Canonical name for a type.
     * @param type A class , which may be a primitive TYPE field.
     * @return Canonical name.
     */
    public static String toName(Class<?> type)
    {
        return class2Name.get(type);
    }

    /* ------------------------------------------------------------ */
    /** Convert String value to instance.
     * @param type The class of the instance, which may be a primitive TYPE field.
     * @param value The value as a string.
     * @return The value as an Object.
     */
    public static Object valueOf(Class<?> type, String value)
    {
        try
        {
            if (type.equals(java.lang.String.class))
                return value;

            Method m = class2Value.get(type);
            if (m!=null)
                return m.invoke(null, value);

            if (type.equals(java.lang.Character.TYPE) ||
                type.equals(java.lang.Character.class))
                return new Character(value.charAt(0));

            Constructor<?> c = type.getConstructor(java.lang.String.class);
            return c.newInstance(value);
        }
        catch(NoSuchMethodException e)
        {
            // LogSupport.ignore(log,e);
        }
        catch(IllegalAccessException e)
        {
            // LogSupport.ignore(log,e);
        }
        catch(InstantiationException e)
        {
            // LogSupport.ignore(log,e);
        }
        catch(InvocationTargetException e)
        {
            if (e.getTargetException() instanceof Error)
                throw (Error)(e.getTargetException());
            // LogSupport.ignore(log,e);
        }
        return null;
    }

    /* ------------------------------------------------------------ */
    /** Convert String value to instance.
     * @param type classname or type (eg int)
     * @param value The value as a string.
     * @return The value as an Object.
     */
    public static Object valueOf(String type, String value)
    {
        return valueOf(fromName(type),value);
    }

    /* ------------------------------------------------------------ */
    /** Parse an int from a substring.
     * Negative numbers are not handled.
     * @param s String
     * @param offset Offset within string
     * @param length Length of integer or -1 for remainder of string
     * @param base base of the integer
     * @return the parsed integer
     * @throws NumberFormatException if the string cannot be parsed
     */
    public static int parseInt(String s, int offset, int length, int base)
        throws NumberFormatException
    {
        int value=0;

        if (length<0)
            length=s.length()-offset;

        for (int i=0;i<length;i++)
        {
            char c=s.charAt(offset+i);

            int digit=convertHexDigit((int)c);
            if (digit<0 || digit>=base)
                throw new NumberFormatException(s.substring(offset,offset+length));
            value=value*base+digit;
        }
        return value;
    }

    /* ------------------------------------------------------------ */
    /** Parse an int from a byte array of ascii characters.
     * Negative numbers are not handled.
     * @param b byte array
     * @param offset Offset within string
     * @param length Length of integer or -1 for remainder of string
     * @param base base of the integer
     * @return the parsed integer
     * @throws NumberFormatException if the array cannot be parsed into an integer
     */
    public static int parseInt(byte[] b, int offset, int length, int base)
        throws NumberFormatException
    {
        int value=0;

        if (length<0)
            length=b.length-offset;

        for (int i=0;i<length;i++)
        {
            char c=(char)(0xff&b[offset+i]);

            int digit=c-'0';
            if (digit<0 || digit>=base || digit>=10)
            {
                digit=10+c-'A';
                if (digit<10 || digit>=base)
                    digit=10+c-'a';
            }
            if (digit<0 || digit>=base)
                throw new NumberFormatException(new String(b,offset,length));
            value=value*base+digit;
        }
        return value;
    }

    /* ------------------------------------------------------------ */
    public static byte[] parseBytes(String s, int base)
    {
        byte[] bytes=new byte[s.length()/2];
        for (int i=0;i<s.length();i+=2)
            bytes[i/2]=(byte)TypeUtil.parseInt(s,i,2,base);
        return bytes;
    }

    /* ------------------------------------------------------------ */
    public static String toString(byte[] bytes, int base)
    {
        StringBuilder buf = new StringBuilder();
        for (byte b : bytes)
        {
            int bi=0xff&b;
            int c='0'+(bi/base)%base;
            if (c>'9')
                c= 'a'+(c-'0'-10);
            buf.append((char)c);
            c='0'+bi%base;
            if (c>'9')
                c= 'a'+(c-'0'-10);
            buf.append((char)c);
        }
        return buf.toString();
    }

    /* ------------------------------------------------------------ */
    /**
     * @param c An ASCII encoded character 0-9 a-f A-F
     * @return The byte value of the character 0-16.
     */
    public static byte convertHexDigit( byte c )
    {
        byte b = (byte)((c & 0x1f) + ((c >> 6) * 0x19) - 0x10);
        if (b<0 || b>15)
            throw new IllegalArgumentException("!hex "+c);
        return b;
    }
    
    /* ------------------------------------------------------------ */
    /**
     * @param c An ASCII encoded character 0-9 a-f A-F
     * @return The byte value of the character 0-16.
     */
    public static int convertHexDigit( int c )
    {
        int d= ((c & 0x1f) + ((c >> 6) * 0x19) - 0x10);
        if (d<0 || d>15)
            throw new NumberFormatException("!hex "+c);
        return d;
    }

    /* ------------------------------------------------------------ */
    public static void toHex(byte b,Appendable buf)
    {
        try
        {
            int d=0xf&((0xF0&b)>>4);
            buf.append((char)((d>9?('A'-10):'0')+d));
            d=0xf&b;
            buf.append((char)((d>9?('A'-10):'0')+d));
        }
        catch(IOException e)
        {
            throw new RuntimeException(e);
        }
    }

    /* ------------------------------------------------------------ */
    public static void toHex(int value,Appendable buf) throws IOException
    {
        int d=0xf&((0xF0000000&value)>>28);
        buf.append((char)((d>9?('A'-10):'0')+d));
        d=0xf&((0x0F000000&value)>>24);
        buf.append((char)((d>9?('A'-10):'0')+d));
        d=0xf&((0x00F00000&value)>>20);
        buf.append((char)((d>9?('A'-10):'0')+d));
        d=0xf&((0x000F0000&value)>>16);
        buf.append((char)((d>9?('A'-10):'0')+d));
        d=0xf&((0x0000F000&value)>>12);
        buf.append((char)((d>9?('A'-10):'0')+d));
        d=0xf&((0x00000F00&value)>>8);
        buf.append((char)((d>9?('A'-10):'0')+d));
        d=0xf&((0x000000F0&value)>>4);
        buf.append((char)((d>9?('A'-10):'0')+d));
        d=0xf&value;
        buf.append((char)((d>9?('A'-10):'0')+d));
    
        Integer.toString(0,36);
    }
    
    
    /* ------------------------------------------------------------ */
    public static void toHex(long value,Appendable buf) throws IOException
    {
        toHex((int)(value>>32),buf);
        toHex((int)value,buf);
    }

    /* ------------------------------------------------------------ */
    public static String toHexString(byte b)
    {
        return toHexString(new byte[]{b}, 0, 1);
    }
    
    /* ------------------------------------------------------------ */
    public static String toHexString(byte[] b)
    {
        return toHexString(b, 0, b.length);
    }

    /* ------------------------------------------------------------ */
    public static String toHexString(byte[] b,int offset,int length)
    {
        StringBuilder buf = new StringBuilder();
        for (int i=offset;i<offset+length;i++)
        {
            int bi=0xff&b[i];
            int c='0'+(bi/16)%16;
            if (c>'9')
                c= 'A'+(c-'0'-10);
            buf.append((char)c);
            c='0'+bi%16;
            if (c>'9')
                c= 'a'+(c-'0'-10);
            buf.append((char)c);
        }
        return buf.toString();
    }

    /* ------------------------------------------------------------ */
    public static byte[] fromHexString(String s)
    {
        if (s.length()%2!=0)
            throw new IllegalArgumentException(s);
        byte[] array = new byte[s.length()/2];
        for (int i=0;i<array.length;i++)
        {
            int b = Integer.parseInt(s.substring(i*2,i*2+2),16);
            array[i]=(byte)(0xff&b);
        }
        return array;
    }


    public static void dump(Class<?> c)
    {
        System.err.println("Dump: "+c);
        dump(c.getClassLoader());
    }

    public static void dump(ClassLoader cl)
    {
        System.err.println("Dump Loaders:");
        while(cl!=null)
        {
            System.err.println("  loader "+cl);
            cl = cl.getParent();
        }
    }


    /* ------------------------------------------------------------ */
    /**
     * @deprecated
     */
    public static byte[] readLine(InputStream in) throws IOException
    {
        byte[] buf = new byte[256];

        int i=0;
        int loops=0;
        int ch=0;

        while (true)
        {
            ch=in.read();
            if (ch<0)
                break;
            loops++;

            // skip a leading LF's
            if (loops==1 && ch==LF)
                continue;

            if (ch==CR || ch==LF)
                break;

            if (i>=buf.length)
            {
                byte[] old_buf=buf;
                buf=new byte[old_buf.length+256];
                System.arraycopy(old_buf, 0, buf, 0, old_buf.length);
            }
            buf[i++]=(byte)ch;
        }

        if (ch==-1 && i==0)
            return null;

        // skip a trailing LF if it exists
        if (ch==CR && in.available()>=1 && in.markSupported())
        {
            in.mark(1);
            ch=in.read();
            if (ch!=LF)
                in.reset();
        }

        byte[] old_buf=buf;
        buf=new byte[i];
        System.arraycopy(old_buf, 0, buf, 0, i);

        return buf;
    }

    public static URL jarFor(String className)
    {
        try
        {
            className=className.replace('.','/')+".class";
            // hack to discover jstl libraries
            URL url = Loader.getResource(null,className,false);
            String s=url.toString();
            if (s.startsWith("jar:file:"))
                return new URL(s.substring(4,s.indexOf("!/")));
        }
        catch(Exception e)
        {
            LOG.ignore(e);
        }
        return null;
    }
    
    public static Object call(Class<?> oClass, String method, Object obj, Object[] arg) 
       throws InvocationTargetException, NoSuchMethodException
    {
        // Lets just try all methods for now
        Method[] methods = oClass.getMethods();
        for (int c = 0; methods != null && c < methods.length; c++)
        {
            if (!methods[c].getName().equals(method))
                continue;
            if (methods[c].getParameterTypes().length != arg.length)
                continue;
            if (Modifier.isStatic(methods[c].getModifiers()) != (obj == null))
                continue;
            if ((obj == null) && methods[c].getDeclaringClass() != oClass)
                continue;

            try
            {
                return methods[c].invoke(obj,arg);
            }
            catch (IllegalAccessException e)
            {
                LOG.ignore(e);
            }
            catch (IllegalArgumentException e)
            {
                LOG.ignore(e);
            }
        }

        throw new NoSuchMethodException(method);
    }
}
