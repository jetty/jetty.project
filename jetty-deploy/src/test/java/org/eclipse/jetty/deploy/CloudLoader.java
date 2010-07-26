package org.eclipse.jetty.deploy;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.webapp.WebAppClassLoader;

public class CloudLoader extends WebAppClassLoader
{
    final WebAppClassLoader.Context _context;
    
    /* ------------------------------------------------------------ */
    public CloudLoader(WebAppClassLoader.Context context)
        throws IOException
    {
        super(context);
        _context=context;
    }
    
    /* ------------------------------------------------------------ */
    public CloudLoader(ClassLoader parent,WebAppClassLoader.Context context)
        throws IOException
    {
        super(parent,context);
        _context=context;
    }
    
    /* ------------------------------------------------------------ */
    /**
     * @see java.net.URLClassLoader#findClass(java.lang.String)
     */
    @Override
    protected Class<?> findClass(String name) throws ClassNotFoundException
    {
        Class<?> clazz = super.findClass(name);
        
        if (clazz!=null && clazz.getClassLoader()==this)
        {
            boolean has_non_final_static_fields=false;
            for (Field field : clazz.getFields())
            {
                int mods = field.getModifiers();
                if (Modifier.isStatic(mods) && !Modifier.isFinal(mods))
                {
                    has_non_final_static_fields=true;
                }
            }

            if (has_non_final_static_fields && !_context.isSystemClass(name))
            {
                Log.info("Has non-final static fields: "+name);
            }
        }
        return clazz;
        
    }
    

}
