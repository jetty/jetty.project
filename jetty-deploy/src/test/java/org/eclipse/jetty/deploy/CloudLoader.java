package org.eclipse.jetty.deploy;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Enumeration;

import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.webapp.ClasspathPattern;
import org.eclipse.jetty.webapp.WebAppClassLoader;

public class CloudLoader extends URLClassLoader
{
    final WebAppClassLoader _parent;
    ClasspathPattern _localPaths = new ClasspathPattern();
    
    /* ------------------------------------------------------------ */
    public CloudLoader(WebAppClassLoader parent)
        throws IOException
    {
        super(parent.getURLs(),parent);
        _parent=parent;
    }
    
    /* ------------------------------------------------------------ */
    /** Add a local class pattern
     * <p>Add a pattern as defined by {@link ClasspathPattern}.
     */
    public void addPattern(String pattern)
    {
        _localPaths.addPattern(pattern);
    }

    /* ------------------------------------------------------------ */
    public boolean isLocal(String name)
    {
        return _localPaths.match(name) && !_parent.getContext().isSystemClass(name);
    }
    
    /* ------------------------------------------------------------ */
    @Override
    public Enumeration<URL> getResources(String name) throws IOException
    {
        return isLocal(name)?findResources(name):_parent.getResources(name);
    }

    /* ------------------------------------------------------------ */
    @Override
    public URL getResource(String name)
    {
        if (isLocal(name))
        {
            URL url= this.findResource(name);

            if (url == null && name.startsWith("/"))
            {
                if (Log.isDebugEnabled())
                    Log.debug("HACK leading / off " + name);
                url= this.findResource(name.substring(1));
            }
            if (url!=null)
                return url;
        }
        return _parent.getResource(name);
    }


    /* ------------------------------------------------------------ */
    @Override
    protected synchronized Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException
    {
        Class<?> c= findLoadedClass(name);
     
        if (c==null && isLocal(name))
        {
            try
            {
                c= this.findClass(name);
            }
            catch (ClassNotFoundException e)
            {
                Log.ignore(e);
            }
        }

        if (c == null)
            c= _parent.loadClass(name);

        if (resolve)
            resolveClass(c);

        if (Log.isDebugEnabled())
            Log.debug("loaded " + c+ " from "+c.getClassLoader());

        // if loaded from direct parent, then scan for non final statics
        // look for non final static fields
        boolean has_non_final_static_fields=false;
        for (Field field : c.getDeclaredFields())
        {
            int mods = field.getModifiers();
            if (Modifier.isStatic(mods) && !Modifier.isFinal(mods))
            {
                has_non_final_static_fields=true;
            }
        }

        if (has_non_final_static_fields)
        {
            if (c.getClassLoader()==_parent)
                Log.warn(name+" loaded from "+c.getClassLoader()+" has non-final static fields");
            else
                Log.debug(name+" loaded from "+c.getClassLoader()+" has non-final static fields");
        }
        
        
        return c;
    }

}
