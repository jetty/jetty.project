package org.eclipse.jetty.annotations;

import java.util.List;

import javax.servlet.annotation.HandlesTypes;

import org.eclipse.jetty.annotations.AnnotationParser.AnnotationHandler;
import org.eclipse.jetty.annotations.AnnotationParser.AnnotationNameValue;
import org.eclipse.jetty.plus.annotation.ContainerInitializer;
import org.eclipse.jetty.util.Loader;
import org.eclipse.jetty.util.log.Log;

/**
 * HandlesTypesAnnotationHandler
 *
 * 
 */
public class ContainerInitializerAnnotationHandler implements AnnotationHandler
{
    ContainerInitializer _initializer;
    Class _annotation;

    public ContainerInitializerAnnotationHandler (ContainerInitializer initializer, Class annotation)
    {
        _initializer = initializer;
        _annotation = annotation;
    }
    
    /** 
     * Handle finding a class that is annotated with the annotation we were constructed with.
     * @see org.eclipse.jetty.annotations.AnnotationParser.AnnotationHandler#handleClass(java.lang.String, int, int, java.lang.String, java.lang.String, java.lang.String[], java.lang.String, java.util.List)
     */
    public void handleClass(String className, int version, int access, String signature, String superName, String[] interfaces, String annotationName,
                            List<AnnotationNameValue> values)
    {
        Class clazz = null;
        
        System.err.println("Got annotated class "+className);
        try
        {
           clazz = Loader.loadClass(null, className);
            _initializer.addApplicableClass(clazz);
        }
        catch (Exception e)
        {
            Log.warn(e);
            return;
        } 
    }

    public void handleField(String className, String fieldName, int access, String fieldType, String signature, Object value, String annotation,
                            List<AnnotationNameValue> values)
    {
        //Not valid on fields
    }

    public void handleMethod(String className, String methodName, int access, String params, String signature, String[] exceptions, String annotation,
                             List<AnnotationNameValue> values)
    {
       //not valid on methods
    }

}
