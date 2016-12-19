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

package org.eclipse.jetty.jmx;

import java.lang.annotation.Annotation;
import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import javax.management.Attribute;
import javax.management.AttributeList;
import javax.management.AttributeNotFoundException;
import javax.management.DynamicMBean;
import javax.management.InvalidAttributeValueException;
import javax.management.MBeanAttributeInfo;
import javax.management.MBeanConstructorInfo;
import javax.management.MBeanException;
import javax.management.MBeanInfo;
import javax.management.MBeanNotificationInfo;
import javax.management.MBeanOperationInfo;
import javax.management.MBeanParameterInfo;
import javax.management.ObjectName;
import javax.management.ReflectionException;
import javax.management.modelmbean.ModelMBean;

import org.eclipse.jetty.util.Loader;
import org.eclipse.jetty.util.TypeUtil;
import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.eclipse.jetty.util.annotation.ManagedOperation;
import org.eclipse.jetty.util.annotation.Name;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

/** 
 * ObjectMBean.
 * <p>
 * A dynamic MBean that can wrap an arbitary Object instance.
 * the attributes and methods exposed by this bean are controlled by
 * the merge of property bundles discovered by names related to all
 * superclasses and all superinterfaces.
 * <p>
 * Attributes and methods exported may be "Object" and must exist on the
 * wrapped object, or "MBean" and must exist on a subclass of OBjectMBean
 * or "MObject" which exists on the wrapped object, but whose values are
 * converted to MBean object names.
 */
public class ObjectMBean implements DynamicMBean
{
    private static final Logger LOG = Log.getLogger(ObjectMBean.class);

    private static Class<?>[] OBJ_ARG = new Class[]{Object.class};

    protected Object _managed;
    private MBeanInfo _info;
    private Map<String, Method> _getters=new HashMap<String, Method>();
    private Map<String, Method> _setters=new HashMap<String, Method>();
    private Map<String, Method> _methods=new HashMap<String, Method>();

    // set of attributes mined from influence hierarchy
    private Set<String> _attributes = new HashSet<String>();

    // set of attributes that are automatically converted to ObjectName
    // as they represent other managed beans which can be linked to
    private Set<String> _convert=new HashSet<String>();
    private ClassLoader _loader;
    private MBeanContainer _mbeanContainer;

    private static String OBJECT_NAME_CLASS = ObjectName.class.getName();
    private static String OBJECT_NAME_ARRAY_CLASS = ObjectName[].class.getName();

    /* ------------------------------------------------------------ */
    /**
     * Create MBean for Object. Attempts to create an MBean for the object by searching the package
     * and class name space. For example an object of the type
     *
     * <PRE>
     * class com.acme.MyClass extends com.acme.util.BaseClass implements com.acme.Iface
     * </PRE>
     *
     * Then this method would look for the following classes:
     * <UL>
     * <LI>com.acme.jmx.MyClassMBean
     * <LI>com.acme.util.jmx.BaseClassMBean
     * <LI>org.eclipse.jetty.jmx.ObjectMBean
     * </UL>
     *
     * @param o The object
     * @return A new instance of an MBean for the object or null.
     */
    public static Object mbeanFor(Object o)
    {
        try
        {
            Class<?> oClass = o.getClass();
            Object mbean = null;

            while ( mbean == null && oClass != null )
            {
                String pName = oClass.getPackage().getName();
                String cName = oClass.getName().substring(pName.length() + 1);
                String mName = pName + ".jmx." + cName + "MBean";

                try
                { 
                    Class<?> mClass;
                    try
                    {
                        // Look for an MBean class from the same loader that loaded the original class
                        mClass = (Object.class.equals(oClass))?oClass=ObjectMBean.class:Loader.loadClass(oClass,mName);
                    }
                    catch (ClassNotFoundException e)
                    {
                        // Not found, so if not the same as the thread context loader, try that.
                        if (Thread.currentThread().getContextClassLoader()==oClass.getClassLoader())
                            throw e;
                        LOG.ignore(e);
                        mClass=Loader.loadClass(oClass,mName);
                    }

                    if (LOG.isDebugEnabled())
                        LOG.debug("ObjectMbean: mbeanFor {} mClass={}", o, mClass);

                    try
                    {
                        Constructor<?> constructor = mClass.getConstructor(OBJ_ARG);
                        mbean=constructor.newInstance(new Object[]{o});
                    }
                    catch(Exception e)
                    {
                        LOG.ignore(e);
                        if (ModelMBean.class.isAssignableFrom(mClass))
                        {
                            mbean=mClass.newInstance();
                            ((ModelMBean)mbean).setManagedResource(o, "objectReference");
                        }
                    }

                    if (LOG.isDebugEnabled())
                        LOG.debug("mbeanFor {} is {}", o, mbean);

                    return mbean;
                }
                catch (ClassNotFoundException e)
                {
                    // The code below was modified to fix bugs 332200 and JETTY-1416
                    // The issue was caused by additional information added to the
                    // message after the class name when running in Apache Felix,
                    // as well as before the class name when running in JBoss.
                    if (e.getMessage().contains(mName))
                        LOG.ignore(e);
                    else
                        LOG.warn(e);
                }
                catch (Error e)
                {
                    LOG.warn(e);
                    mbean = null;
                }
                catch (Exception e)
                {
                    LOG.warn(e);
                    mbean = null;
                }

                oClass = oClass.getSuperclass();
            }
        }
        catch (Exception e)
        {
            LOG.ignore(e);
        }

        return null;
    }


    public ObjectMBean(Object managedObject)
    {
        _managed = managedObject;
        _loader = Thread.currentThread().getContextClassLoader();
    }

    public Object getManagedObject()
    {
        return _managed;
    }

    public ObjectName getObjectName()
    {
        return null;
    }

    public String getObjectContextBasis()
    {
        return null;
    }

    public String getObjectNameBasis()
    {
        return null;
    }

    protected void setMBeanContainer(MBeanContainer container)
    {
       this._mbeanContainer = container;
    }

    public MBeanContainer getMBeanContainer ()
    {
        return this._mbeanContainer;
    }


    public MBeanInfo getMBeanInfo()
    {
        try
        {
            if (_info==null)
            {
                // Start with blank lazy lists attributes etc.
                String desc=null;
                List<MBeanAttributeInfo> attributes = new ArrayList<MBeanAttributeInfo>();
                List<MBeanConstructorInfo> constructors = new ArrayList<MBeanConstructorInfo>();
                List<MBeanOperationInfo> operations = new ArrayList<MBeanOperationInfo>();
                List<MBeanNotificationInfo> notifications = new ArrayList<MBeanNotificationInfo>();

                // Find list of classes that can influence the mbean
                Class<?> o_class=_managed.getClass();
                List<Class<?>> influences = new ArrayList<Class<?>>();
                influences.add(this.getClass()); // always add MBean itself
                influences = findInfluences(influences, _managed.getClass());

                if (LOG.isDebugEnabled())
                    LOG.debug("Influence Count: {}", influences.size() );

                // Process Type Annotations
                ManagedObject primary = o_class.getAnnotation( ManagedObject.class);

                if ( primary != null )
                {
                    desc = primary.value();
                }
                else
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug("No @ManagedObject declared on {}", _managed.getClass());
                }


                // For each influence
                for (int i=0;i<influences.size();i++)
                {
                    Class<?> oClass = influences.get(i);

                    ManagedObject typeAnnotation = oClass.getAnnotation( ManagedObject.class );

                    if (LOG.isDebugEnabled())
                        LOG.debug("Influenced by: " + oClass.getCanonicalName() );

                    if ( typeAnnotation == null )
                    {
                        if (LOG.isDebugEnabled())
                            LOG.debug("Annotations not found for: {}", oClass.getCanonicalName() );
                        continue;
                    }

                    // Process Method Annotations

                    for (Method method : oClass.getDeclaredMethods())
                    {
                        ManagedAttribute methodAttributeAnnotation = method.getAnnotation(ManagedAttribute.class);

                        if (methodAttributeAnnotation != null)
                        {
                            // TODO sort out how a proper name could get here, its a method name as an attribute at this point.
                            if (LOG.isDebugEnabled())
                                LOG.debug("Attribute Annotation found for: {}", method.getName());
                            MBeanAttributeInfo mai = defineAttribute(method,methodAttributeAnnotation);
                            if ( mai != null )
                            {
                                attributes.add(mai);
                            }
                        }

                        ManagedOperation methodOperationAnnotation = method.getAnnotation(ManagedOperation.class);

                        if (methodOperationAnnotation != null)
                        {
                            if (LOG.isDebugEnabled())
                                LOG.debug("Method Annotation found for: {}", method.getName());
                            MBeanOperationInfo oi = defineOperation(method,methodOperationAnnotation);
                            if (oi != null)
                            {
                                operations.add(oi);
                            }
                        }
                    }

                }

                _info = new MBeanInfo(o_class.getName(),
                                desc,
                                (MBeanAttributeInfo[])attributes.toArray(new MBeanAttributeInfo[attributes.size()]),
                                (MBeanConstructorInfo[])constructors.toArray(new MBeanConstructorInfo[constructors.size()]),
                                (MBeanOperationInfo[])operations.toArray(new MBeanOperationInfo[operations.size()]),
                                (MBeanNotificationInfo[])notifications.toArray(new MBeanNotificationInfo[notifications.size()]));
            }
        }
        catch(RuntimeException e)
        {
            LOG.warn(e);
            throw e;
        }
        return _info;
    }


    /* ------------------------------------------------------------ */
    public Object getAttribute(String name) throws AttributeNotFoundException, MBeanException, ReflectionException
    {
        Method getter = (Method) _getters.get(name);
        if (getter == null)
        {
            throw new AttributeNotFoundException(name);
        }

        try
        {
            Object o = _managed;
            if (getter.getDeclaringClass().isInstance(this))
                o = this; // mbean method

            // get the attribute
            Object r=getter.invoke(o, (java.lang.Object[]) null);

            // convert to ObjectName if the type has the @ManagedObject annotation
            if (r!=null )
            {
                if (r.getClass().isArray())
                {
                    if (r.getClass().getComponentType().isAnnotationPresent(ManagedObject.class))
                    {
                        ObjectName[] on = new ObjectName[Array.getLength(r)];
                        for (int i = 0; i < on.length; i++)
                        {
                            on[i] = _mbeanContainer.findMBean(Array.get(r,i));
                        }
                        r = on;
                    }
                }
                else if (r instanceof Collection<?>)
                {
                    @SuppressWarnings("unchecked")
                    Collection<Object> c = (Collection<Object>)r;

                    if (!c.isEmpty() && c.iterator().next().getClass().isAnnotationPresent(ManagedObject.class))
                    {
                        // check the first thing out

                        ObjectName[] on = new ObjectName[c.size()];
                        int i = 0;
                        for (Object obj : c)
                        {
                            on[i++] = _mbeanContainer.findMBean(obj);
                        }
                        r = on;
                    }
                }
                else
                {
                    Class<?> clazz = r.getClass();
                    
                    while (clazz != null)
                    {
                        if (clazz.isAnnotationPresent(ManagedObject.class))
                        {
                            ObjectName mbean = _mbeanContainer.findMBean(r);

                            if (mbean != null)
                            {    
                                return mbean;
                            }
                            else
                            {
                                return null;
                            }
                        }                   
                        clazz = clazz.getSuperclass();
                    }              
                }
            }

            return r;
        }
        catch (IllegalAccessException e)
        {
            LOG.warn(Log.EXCEPTION, e);
            throw new AttributeNotFoundException(e.toString());
        }
        catch (InvocationTargetException e)
        {
            LOG.warn(Log.EXCEPTION, e);
            throw new ReflectionException(new Exception(e.getCause()));
        }
    }

    /* ------------------------------------------------------------ */
    public AttributeList getAttributes(String[] names)
    {
        AttributeList results = new AttributeList(names.length);
        for (int i = 0; i < names.length; i++)
        {
            try
            {
                results.add(new Attribute(names[i], getAttribute(names[i])));
            }
            catch (Exception e)
            {
                LOG.warn(Log.EXCEPTION, e);
            }
        }
        return results;
    }

    /* ------------------------------------------------------------ */
    public void setAttribute(Attribute attr) throws AttributeNotFoundException, InvalidAttributeValueException, MBeanException, ReflectionException
    {
        if (attr == null)
            return;

        if (LOG.isDebugEnabled())
            LOG.debug("setAttribute " + _managed + ":" +attr.getName() + "=" + attr.getValue());
        Method setter = (Method) _setters.get(attr.getName());
        if (setter == null)
            throw new AttributeNotFoundException(attr.getName());
        try
        {
            Object o = _managed;
            if (setter.getDeclaringClass().isInstance(this))
                o = this;

            // get the value
            Object value = attr.getValue();

            // convert from ObjectName if need be
            if (value!=null && _convert.contains(attr.getName()))
            {
                if (value.getClass().isArray())
                {
                    Class<?> t=setter.getParameterTypes()[0].getComponentType();
                    Object na = Array.newInstance(t,Array.getLength(value));
                    for (int i=Array.getLength(value);i-->0;)
                        Array.set(na, i, _mbeanContainer.findBean((ObjectName)Array.get(value, i)));
                    value=na;
                }
                else
                    value=_mbeanContainer.findBean((ObjectName)value);
            }

            // do the setting
            setter.invoke(o, new Object[]{ value });
        }
        catch (IllegalAccessException e)
        {
            LOG.warn(Log.EXCEPTION, e);
            throw new AttributeNotFoundException(e.toString());
        }
        catch (InvocationTargetException e)
        {
            LOG.warn(Log.EXCEPTION, e);
            throw new ReflectionException(new Exception(e.getCause()));
        }
    }

    /* ------------------------------------------------------------ */
    public AttributeList setAttributes(AttributeList attrs)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("setAttributes");

        AttributeList results = new AttributeList(attrs.size());
        Iterator<Object> iter = attrs.iterator();
        while (iter.hasNext())
        {
            try
            {
                Attribute attr = (Attribute) iter.next();
                setAttribute(attr);
                results.add(new Attribute(attr.getName(), getAttribute(attr.getName())));
            }
            catch (Exception e)
            {
                LOG.warn(Log.EXCEPTION, e);
            }
        }
        return results;
    }

    /* ------------------------------------------------------------ */
    public Object invoke(String name, Object[] params, String[] signature) throws MBeanException, ReflectionException
    {
        if (LOG.isDebugEnabled())
            LOG.debug("ObjectMBean:invoke " + name);

        String methodKey = name + "(";
        if (signature != null)
            for (int i = 0; i < signature.length; i++)
                methodKey += (i > 0 ? "," : "") + signature[i];
        methodKey += ")";

        ClassLoader old_loader=Thread.currentThread().getContextClassLoader();
        try
        {
            Thread.currentThread().setContextClassLoader(_loader);
            Method method = (Method) _methods.get(methodKey);
            if (method == null)
                throw new NoSuchMethodException(methodKey);

            Object o = _managed;

            if (method.getDeclaringClass().isInstance(this))
            {
                o = this;
            }
            return method.invoke(o, params);
        }
        catch (NoSuchMethodException e)
        {
            LOG.warn(Log.EXCEPTION, e);
            throw new ReflectionException(e);
        }
        catch (IllegalAccessException e)
        {
            LOG.warn(Log.EXCEPTION, e);
            throw new MBeanException(e);
        }
        catch (InvocationTargetException e)
        {
            LOG.warn(Log.EXCEPTION, e);
            throw new ReflectionException(new Exception(e.getCause()));
        }
        finally
        {
            Thread.currentThread().setContextClassLoader(old_loader);
        }
    }

    private static List<Class<?>> findInfluences(List<Class<?>> influences, Class<?> aClass)
    {
        if (aClass != null)
        {
            if (!influences.contains(aClass))
            {
                // This class is a new influence
                influences.add(aClass);
            }

            // So are the super classes
            influences = findInfluences(influences,aClass.getSuperclass());

            // So are the interfaces
            Class<?>[] ifs = aClass.getInterfaces();
            for (int i = 0; ifs != null && i < ifs.length; i++)
            {
                influences = findInfluences(influences,ifs[i]);
            }
        }

        return influences;
    }

    /* ------------------------------------------------------------ */
    /**
     * TODO update to new behavior
     *
     * Define an attribute on the managed object. The meta data is defined by looking for standard
     * getter and setter methods. Descriptions are obtained with a call to findDescription with the
     * attribute name.
     *
     * @param method the method to define
     * @param attributeAnnotation "description" or "access:description" or "type:access:description"  where type is
     * one of: <ul>
     * <li>"Object" The field/method is on the managed object.
     * <li>"MBean" The field/method is on the mbean proxy object
     * <li>"MObject" The field/method is on the managed object and value should be converted to MBean reference
     * <li>"MMBean" The field/method is on the mbean proxy object and value should be converted to MBean reference
     * </ul>
     * the access is either "RW" or "RO".
     * @return the mbean attribute info for the method
     */
    public MBeanAttributeInfo defineAttribute(Method method, ManagedAttribute attributeAnnotation)
    {
        // determine the name of the managed attribute
        String name = attributeAnnotation.name();

        if ("".equals(name))
        {
            name = toVariableName(method.getName());
        }

        if ( _attributes.contains(name))
        {
            return null; // we have an attribute named this already
        }

        String description = attributeAnnotation.value();
        boolean readonly = attributeAnnotation.readonly();
        boolean onMBean = attributeAnnotation.proxied();

        boolean convert = false;

        // determine if we should convert
        Class<?> return_type = method.getReturnType();

        // get the component type
        Class<?> component_type = return_type;
        while ( component_type.isArray() )
        {
            component_type = component_type.getComponentType();
        }
           
        // Test to see if the returnType or any of its super classes are managed objects
        convert = isAnnotationPresent(component_type, ManagedObject.class);       
        
        String uName = name.substring(0, 1).toUpperCase(Locale.ENGLISH) + name.substring(1);
        Class<?> oClass = onMBean ? this.getClass() : _managed.getClass();

        if (LOG.isDebugEnabled())
            LOG.debug("defineAttribute {} {}:{}:{}:{}",name,onMBean,readonly,oClass,description);

        Method setter = null;

        // dig out a setter if one exists
        if (!readonly)
        {
            String declaredSetter = attributeAnnotation.setter();

            if (LOG.isDebugEnabled())
                LOG.debug("DeclaredSetter: {}", declaredSetter);

            Method[] methods = oClass.getMethods();
            for (int m = 0; m < methods.length; m++)
            {
                if ((methods[m].getModifiers() & Modifier.PUBLIC) == 0)
                    continue;

                if (!"".equals(declaredSetter))
                {

                    // look for a declared setter
                    if (methods[m].getName().equals(declaredSetter) && methods[m].getParameterCount() == 1)
                    {
                        if (setter != null)
                        {
                            LOG.warn("Multiple setters for mbean attr {} in {}", name, oClass);
                            continue;
                        }
                        setter = methods[m];
                        if ( !component_type.equals(methods[m].getParameterTypes()[0]))
                        {
                            LOG.warn("Type conflict for mbean attr {} in {}", name, oClass);
                            continue;
                        }
                        if (LOG.isDebugEnabled())
                            LOG.debug("Declared Setter: " + declaredSetter);
                    }
                }

                // look for a setter
                if ( methods[m].getName().equals("set" + uName) && methods[m].getParameterCount() == 1)
                {
                    if (setter != null)
                    {
                        LOG.warn("Multiple setters for mbean attr {} in {}", name, oClass);
                        continue;
                    }
                    setter = methods[m];
                    if ( !return_type.equals(methods[m].getParameterTypes()[0]))
                    {                            
                        LOG.warn("Type conflict for mbean attr {} in {}", name, oClass);
                        continue;
                    }
                }
            }
        }

        if (convert)
        {
            if (component_type==null)
            {
                LOG.warn("No mbean type for {} on {}", name, _managed.getClass());
                return null;
            }

            if (component_type.isPrimitive() && !component_type.isArray())
            {
                LOG.warn("Cannot convert mbean primative {}", name);
                return null;
            }
            if (LOG.isDebugEnabled())
                LOG.debug("passed convert checks {} for type {}", name, component_type);
        }

        try
        {
            // Remember the methods
            _getters.put(name, method);
            _setters.put(name, setter);

            MBeanAttributeInfo info=null;
            if (convert)
            {
                _convert.add(name);

                if (component_type.isArray())
                {
                    info= new MBeanAttributeInfo(name,OBJECT_NAME_ARRAY_CLASS,description,true,setter!=null,method.getName().startsWith("is"));
                }
                else
                {
                    info= new MBeanAttributeInfo(name,OBJECT_NAME_CLASS,description,true,setter!=null,method.getName().startsWith("is"));
                }
            }
            else
            {
                info= new MBeanAttributeInfo(name,description,method,setter);
            }

            _attributes.add(name);
            
            return info;
        }
        catch (Exception e)
        {
            LOG.warn(e);
            throw new IllegalArgumentException(e.toString());
        }
    }


    /* ------------------------------------------------------------ */
    /**
     *  TODO update to new behavior
     *
     * Define an operation on the managed object. Defines an operation with parameters. Refection is
     * used to determine find the method and it's return type. The description of the method is
     * found with a call to findDescription on "name(signature)". The name and description of each
     * parameter is found with a call to findDescription with "name(signature)[n]", the returned
     * description is for the last parameter of the partial signature and is assumed to start with
     * the parameter name, followed by a colon.
     *
     * @param metaData "description" or "impact:description" or "type:impact:description", type is
     * the "Object","MBean", "MMBean" or "MObject" to indicate the method is on the object, the MBean or on the
     * object but converted to an MBean reference, and impact is either "ACTION","INFO","ACTION_INFO" or "UNKNOWN".
     */
    private MBeanOperationInfo defineOperation(Method method, ManagedOperation methodAnnotation)
    {
        String description = methodAnnotation.value();
        boolean onMBean = methodAnnotation.proxied();

        boolean convert = false;

        // determine if we should convert
        Class<?> returnType = method.getReturnType();

        if ( returnType.isArray() )
        {
            if (LOG.isDebugEnabled())
                LOG.debug("returnType is array, get component type");
            returnType = returnType.getComponentType();
        }

        if ( returnType.isAnnotationPresent(ManagedObject.class))
        {
            convert = true;
        }

        String impactName = methodAnnotation.impact();

        if (LOG.isDebugEnabled())
            LOG.debug("defineOperation {} {}:{}:{}", method.getName(), onMBean, impactName, description);

        String signature = method.getName();

        try
        {
            // Resolve the impact
            int impact=MBeanOperationInfo.UNKNOWN;
            if (impactName==null || impactName.equals("UNKNOWN"))
                impact=MBeanOperationInfo.UNKNOWN;
            else if (impactName.equals("ACTION"))
                impact=MBeanOperationInfo.ACTION;
            else if (impactName.equals("INFO"))
                impact=MBeanOperationInfo.INFO;
            else if (impactName.equals("ACTION_INFO"))
                impact=MBeanOperationInfo.ACTION_INFO;
            else
                LOG.warn("Unknown impact '"+impactName+"' for "+signature);


            Annotation[][] allParameterAnnotations = method.getParameterAnnotations();
            Class<?>[] methodTypes = method.getParameterTypes();
            MBeanParameterInfo[] pInfo = new MBeanParameterInfo[allParameterAnnotations.length];

            for ( int i = 0 ; i < allParameterAnnotations.length ; ++i )
            {
                Annotation[] parameterAnnotations = allParameterAnnotations[i];

                for ( Annotation anno : parameterAnnotations )
                {
                    if ( anno instanceof Name )
                    {
                        Name nameAnnotation = (Name) anno;

                        pInfo[i] = new MBeanParameterInfo(nameAnnotation.value(),methodTypes[i].getName(),nameAnnotation.description());
                    }
                }
            }

            signature += "(";
            for ( int i = 0 ; i < methodTypes.length ; ++i )
            {
                signature += methodTypes[i].getName();

                if ( i != methodTypes.length - 1 )
                {
                    signature += ",";
                }
            }
            signature += ")";

            Class<?> returnClass = method.getReturnType();

            if (LOG.isDebugEnabled())
                LOG.debug("Method Cache: " + signature );

            if ( _methods.containsKey(signature) )
            {
                return null; // we have an operation for this already
            }

            _methods.put(signature, method);
            if (convert)
                _convert.add(signature);

            return new MBeanOperationInfo(method.getName(), description, pInfo, returnClass.isPrimitive() ? TypeUtil.toName(returnClass) : (returnClass.getName()), impact);
        }
        catch (Exception e)
        {
            LOG.warn("Operation '"+signature+"'", e);
            throw new IllegalArgumentException(e.toString());
        }

    }

    protected String toVariableName( String methodName )
    {
        String variableName = methodName;

        if ( methodName.startsWith("get") || methodName.startsWith("set") )
        {
            variableName = variableName.substring(3);
        }
        else if ( methodName.startsWith("is") )
        {
            variableName = variableName.substring(2);
        }

        variableName = variableName.substring(0,1).toLowerCase(Locale.ENGLISH) + variableName.substring(1);

        return variableName;
    }
    
    protected boolean isAnnotationPresent(Class<?> clazz, Class<? extends Annotation> annotation)
    {
        Class<?> test = clazz;
        
        while (test != null )
        {  
            if ( test.isAnnotationPresent(annotation))
            {
                return true;
            }
            else
            {
                test = test.getSuperclass();
            }
        }
        return false;
    }
}
