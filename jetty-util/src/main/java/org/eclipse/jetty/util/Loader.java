//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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

import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Locale;
import java.util.MissingResourceException;
import java.util.ResourceBundle;

import org.eclipse.jetty.util.resource.Resource;

/* ------------------------------------------------------------ */
/** ClassLoader Helper.
 * This helper class allows classes to be loaded either from the
 * Thread's ContextClassLoader, the classloader of the derived class
 * or the system ClassLoader.
 *
 * <B>Usage:</B><PRE>
 * public class MyClass {
 *     void myMethod() {
 *          ...
 *          Class c=Loader.loadClass(this.getClass(),classname);
 *          ...
 *     }
 * </PRE>          
 * 
 */
public class Loader
{
    /* ------------------------------------------------------------ */
    public static URL getResource(Class<?> loadClass,String name)
    {
        URL url =null;
        ClassLoader context_loader=Thread.currentThread().getContextClassLoader();
        if (context_loader!=null)
            url=context_loader.getResource(name); 
        
        if (url==null && loadClass!=null)
        {
            ClassLoader load_loader=loadClass.getClassLoader();
            if (load_loader!=null && load_loader!=context_loader)
                url=load_loader.getResource(name);
        }

        if (url==null)
            url=ClassLoader.getSystemResource(name);

        return url;
    }

    /* ------------------------------------------------------------ */
    /** Load a class.
     * 
     * @param loadClass a similar class, belong in the same classloader of the desired class to load
     * @param name the name of the new class to load, using the same ClassLoader as the <code>loadClass</code> 
     * @return Class
     * @throws ClassNotFoundException if not able to find the class
     */
    @SuppressWarnings("rawtypes")
    public static Class loadClass(Class loadClass,String name)
        throws ClassNotFoundException
    {
        ClassNotFoundException ex=null;
        Class<?> c =null;
        ClassLoader context_loader=Thread.currentThread().getContextClassLoader();
        if (context_loader!=null )
        {
            try { c=context_loader.loadClass(name); }
            catch (ClassNotFoundException e) {ex=e;}
        }    
        
        if (c==null && loadClass!=null)
        {
            ClassLoader load_loader=loadClass.getClassLoader();
            if (load_loader!=null && load_loader!=context_loader)
            {
                try { c=load_loader.loadClass(name); }
                catch (ClassNotFoundException e) {if(ex==null)ex=e;}
            }
        }

        if (c==null)
        {
            try { c=Class.forName(name); }
            catch (ClassNotFoundException e) 
            {
                if(ex!=null)
                    throw ex;
                throw e;
            }
        }   

        return c;
    }
    
    
    
    /* ------------------------------------------------------------ */
    public static ResourceBundle getResourceBundle(Class<?> loadClass,String name,boolean checkParents, Locale locale)
        throws MissingResourceException
    {
        MissingResourceException ex=null;
        ResourceBundle bundle =null;
        ClassLoader loader=Thread.currentThread().getContextClassLoader();
        while (bundle==null && loader!=null )
        {
            try { bundle=ResourceBundle.getBundle(name, locale, loader); }
            catch (MissingResourceException e) {if(ex==null)ex=e;}
            loader=(bundle==null&&checkParents)?loader.getParent():null;
        }      
        
        loader=loadClass==null?null:loadClass.getClassLoader();
        while (bundle==null && loader!=null )
        {
            try { bundle=ResourceBundle.getBundle(name, locale, loader); }
            catch (MissingResourceException e) {if(ex==null)ex=e;}
            loader=(bundle==null&&checkParents)?loader.getParent():null;
        }       

        if (bundle==null)
        {
            try { bundle=ResourceBundle.getBundle(name, locale); }
            catch (MissingResourceException e) {if(ex==null)ex=e;}
        }   

        if (bundle!=null)
            return bundle;
        throw ex;
    }
    
    
    /* ------------------------------------------------------------ */
    /**
     * Generate the classpath (as a string) of all classloaders
     * above the given classloader.
     * 
     * This is primarily used for jasper.
     * @param loader the classloader to use
     * @return the system class path
     * @throws Exception if unable to generate the classpath from the resource references
     */
    public static String getClassPath(ClassLoader loader) throws Exception
    {
        StringBuilder classpath=new StringBuilder();
        while (loader != null && (loader instanceof URLClassLoader))
        {
            URL[] urls = ((URLClassLoader)loader).getURLs();
            if (urls != null)
            {     
                for (int i=0;i<urls.length;i++)
                {
                    Resource resource = Resource.newResource(urls[i]);
                    File file=resource.getFile();
                    if (file!=null && file.exists())
                    {
                        if (classpath.length()>0)
                            classpath.append(File.pathSeparatorChar);
                        classpath.append(file.getAbsolutePath());
                    }
                }
            }
            loader = loader.getParent();
        }
        return classpath.toString();
    }
}

