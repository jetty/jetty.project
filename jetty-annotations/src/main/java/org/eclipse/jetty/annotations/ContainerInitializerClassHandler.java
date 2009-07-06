package org.eclipse.jetty.annotations;

import org.eclipse.jetty.annotations.AnnotationParser.ClassHandler;
import org.eclipse.jetty.plus.annotation.ContainerInitializer;
import org.eclipse.jetty.util.Loader;
import org.eclipse.jetty.util.log.Log;

public class ContainerInitializerClassHandler implements ClassHandler
{
    public ContainerInitializer _initializer;
    public Class _handlesTypeClass;
    
    
    public ContainerInitializerClassHandler(ContainerInitializer initializer, Class c)
    {
        super();
        _initializer = initializer;
        _handlesTypeClass = c;
    }


    public void handle(String className, int version, int access, String signature, String superName, String[] interfaces)
    {
        try
        {
            System.err.print("Checking class "+className+" with super "+superName+" interfaces:");
            for (int i=0; interfaces != null && i<interfaces.length;i++)
                System.err.print(interfaces[i]+",");
            System.err.println();
            //Looking at a class - need to check if this class extends or implements the _handlesTypeClass
            if (superName != null && superName.equals(_handlesTypeClass.getName()))
                _initializer.addApplicableClass(Loader.loadClass(null, className));

            for (int i=0; interfaces != null && i<interfaces.length; i++)
            {
                if (interfaces[i].equals(_handlesTypeClass.getName()))
                    _initializer.addApplicableClass(Loader.loadClass(null, className));
            }
        }
        catch (Exception e)
        {
            Log.warn(e);
        }  
    }
}
