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

package org.eclipse.jetty.jmx;

import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Collection;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.MissingResourceException;
import java.util.ResourceBundle;
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

import org.eclipse.jetty.util.LazyList;
import org.eclipse.jetty.util.Loader;
import org.eclipse.jetty.util.TypeUtil;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

/* ------------------------------------------------------------ */
/** ObjectMBean.
 * A dynamic MBean that can wrap an arbitary Object instance.
 * the attributes and methods exposed by this bean are controlled by
 * the merge of property bundles discovered by names related to all
 * superclasses and all superinterfaces.
 *
 * Attributes and methods exported may be "Object" and must exist on the
 * wrapped object, or "MBean" and must exist on a subclass of OBjectMBean
 * or "MObject" which exists on the wrapped object, but whose values are
 * converted to MBean object names.
 *
 */
public class ObjectMBean implements DynamicMBean
{
    private static final Logger LOG = Log.getLogger(ObjectMBean.class);

    private static Class[] OBJ_ARG = new Class[]{Object.class};

    protected Object _managed;
    private MBeanInfo _info;
    private Map _getters=new HashMap();
    private Map _setters=new HashMap();
    private Map _methods=new HashMap();
    private Set _convert=new HashSet();
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
            Class oClass = o.getClass();
            Object mbean = null;

            while (mbean == null && oClass != null)
            {
                String pName = oClass.getPackage().getName();
                String cName = oClass.getName().substring(pName.length() + 1);
                String mName = pName + ".jmx." + cName + "MBean";
                

                try
                {
                    Class mClass = (Object.class.equals(oClass))?oClass=ObjectMBean.class:Loader.loadClass(oClass,mName,true);
                    if (LOG.isDebugEnabled())
                        LOG.debug("mbeanFor " + o + " mClass=" + mClass);

                    try
                    {
                        Constructor constructor = mClass.getConstructor(OBJ_ARG);
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
                        LOG.debug("mbeanFor " + o + " is " + mbean);
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
                Object attributes=null;
                Object constructors=null;
                Object operations=null;
                Object notifications=null;

                // Find list of classes that can influence the mbean
                Class o_class=_managed.getClass();
                Object influences = findInfluences(null, _managed.getClass());

                // Set to record defined items
                Set defined=new HashSet();

                // For each influence
                for (int i=0;i<LazyList.size(influences);i++)
                {
                    Class oClass = (Class)LazyList.get(influences, i);

                    // look for a bundle defining methods
                    if (Object.class.equals(oClass))
                        oClass=ObjectMBean.class;
                    String pName = oClass.getPackage().getName();
                    String cName = oClass.getName().substring(pName.length() + 1);
                    String rName = pName.replace('.', '/') + "/jmx/" + cName+"-mbean";

                    try
                    {
                        LOG.debug(rName);
                        ResourceBundle bundle = Loader.getResourceBundle(o_class, rName,true,Locale.getDefault());

                        
                        // Extract meta data from bundle
                        Enumeration e = bundle.getKeys();
                        while (e.hasMoreElements())
                        {
                            String key = (String)e.nextElement();
                            String value = bundle.getString(key);

                            // Determin if key is for mbean , attribute or for operation
                            if (key.equals(cName))
                            {
                                // set the mbean description
                                if (desc==null)
                                    desc=value;
                            }
                            else if (key.indexOf('(')>0)
                            {
                                // define an operation
                                if (!defined.contains(key) && key.indexOf('[')<0)
                                {
                                    defined.add(key);
                                    operations=LazyList.add(operations,defineOperation(key, value, bundle));
                                }
                            }
                            else
                            {
                                // define an attribute
                                if (!defined.contains(key))
                                {
                                    defined.add(key);
                                    MBeanAttributeInfo info=defineAttribute(key, value);
                                    if (info!=null)
                                        attributes=LazyList.add(attributes,info);
                                }
                            }
                        }
                    }
                    catch(MissingResourceException e)
                    {
                        LOG.ignore(e);
                    }
                }

                _info = new MBeanInfo(o_class.getName(),
                                desc,
                                (MBeanAttributeInfo[])LazyList.toArray(attributes, MBeanAttributeInfo.class),
                                (MBeanConstructorInfo[])LazyList.toArray(constructors, MBeanConstructorInfo.class),
                                (MBeanOperationInfo[])LazyList.toArray(operations, MBeanOperationInfo.class),
                                (MBeanNotificationInfo[])LazyList.toArray(notifications, MBeanNotificationInfo.class));
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
            throw new AttributeNotFoundException(name);
        try
        {
            Object o = _managed;
            if (getter.getDeclaringClass().isInstance(this))
                o = this; // mbean method

            // get the attribute
            Object r=getter.invoke(o, (java.lang.Object[]) null);

            // convert to ObjectName if need be.
            if (r!=null && _convert.contains(name))
            {
                if (r.getClass().isArray())
                {
                    ObjectName[] on = new ObjectName[Array.getLength(r)];
                    for (int i=0;i<on.length;i++)
                        on[i]=_mbeanContainer.findMBean(Array.get(r, i));
                    r=on;
                }
                else if (r instanceof Collection<?>)
                {
                    Collection<Object> c = (Collection<Object>)r;
                    ObjectName[] on = new ObjectName[c.size()];
                    int i=0;
                    for (Object obj :c)
                        on[i++]=_mbeanContainer.findMBean(obj);
                    r=on;
                }
                else
                {
                    ObjectName mbean = _mbeanContainer.findMBean(r);
                    if (mbean==null)
                        return null;
                    r=mbean;
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
                    Class t=setter.getParameterTypes()[0].getComponentType();
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
        LOG.debug("setAttributes");

        AttributeList results = new AttributeList(attrs.size());
        Iterator iter = attrs.iterator();
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
            LOG.debug("invoke " + name);

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
                o = this;
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

    private static Object findInfluences(Object influences, Class aClass)
    {
        if (aClass!=null)
        {
            // This class is an influence
            influences=LazyList.add(influences,aClass);

            // So are the super classes
            influences=findInfluences(influences,aClass.getSuperclass());

            // So are the interfaces
            Class[] ifs = aClass.getInterfaces();
            for (int i=0;ifs!=null && i<ifs.length;i++)
                influences=findInfluences(influences,ifs[i]);
        }
        return influences;
    }

    /* ------------------------------------------------------------ */
    /**
     * Define an attribute on the managed object. The meta data is defined by looking for standard
     * getter and setter methods. Descriptions are obtained with a call to findDescription with the
     * attribute name.
     *
     * @param name
     * @param metaData "description" or "access:description" or "type:access:description"  where type is
     * one of: <ul>
     * <li>"Object" The field/method is on the managed object.
     * <li>"MBean" The field/method is on the mbean proxy object
     * <li>"MObject" The field/method is on the managed object and value should be converted to MBean reference
     * <li>"MMBean" The field/method is on the mbean proxy object and value should be converted to MBean reference
     * </ul>
     * the access is either "RW" or "RO".
     */
    public MBeanAttributeInfo defineAttribute(String name, String metaData)
    {
        String description = "";
        boolean writable = true;
        boolean onMBean = false;
        boolean convert = false;

        if (metaData!= null)
        {
            String[] tokens = metaData.split(":", 3);
            for (int t=0;t<tokens.length-1;t++)
            {
                tokens[t]=tokens[t].trim();
                if ("RO".equals(tokens[t]))
                    writable=false;
                else 
                {
                    onMBean=("MMBean".equalsIgnoreCase(tokens[t]) || "MBean".equalsIgnoreCase(tokens[t]));
                    convert=("MMBean".equalsIgnoreCase(tokens[t]) || "MObject".equalsIgnoreCase(tokens[t]));
                }
            }
            description=tokens[tokens.length-1];
        }
        

        String uName = name.substring(0, 1).toUpperCase(Locale.ENGLISH) + name.substring(1);
        Class oClass = onMBean ? this.getClass() : _managed.getClass();

        if (LOG.isDebugEnabled())
            LOG.debug("defineAttribute "+name+" "+onMBean+":"+writable+":"+oClass+":"+description);

        Class type = null;
        Method getter = null;
        Method setter = null;
        Method[] methods = oClass.getMethods();
        for (int m = 0; m < methods.length; m++)
        {
            if ((methods[m].getModifiers() & Modifier.PUBLIC) == 0)
                continue;

            // Look for a getter
            if (methods[m].getName().equals("get" + uName) && methods[m].getParameterTypes().length == 0)
            {
                if (getter != null)
                {
		    LOG.warn("Multiple mbean getters for attr " + name+ " in "+oClass);
		    continue;
		}
                getter = methods[m];
                if (type != null && !type.equals(methods[m].getReturnType()))
                {
		    LOG.warn("Type conflict for mbean attr " + name+ " in "+oClass);
		    continue;
		}
                type = methods[m].getReturnType();
            }

            // Look for an is getter
            if (methods[m].getName().equals("is" + uName) && methods[m].getParameterTypes().length == 0)
            {
                if (getter != null)
                {
		    LOG.warn("Multiple mbean getters for attr " + name+ " in "+oClass);
		    continue;
		}
                getter = methods[m];
                if (type != null && !type.equals(methods[m].getReturnType()))
                {
		    LOG.warn("Type conflict for mbean attr " + name+ " in "+oClass);
		    continue;
		}
                type = methods[m].getReturnType();
            }

            // look for a setter
            if (writable && methods[m].getName().equals("set" + uName) && methods[m].getParameterTypes().length == 1)
            {
                if (setter != null)
                {
		    LOG.warn("Multiple setters for mbean attr " + name+ " in "+oClass);
		    continue;
		}
                setter = methods[m];
                if (type != null && !type.equals(methods[m].getParameterTypes()[0]))
                {
		    LOG.warn("Type conflict for mbean attr " + name+ " in "+oClass);
		    continue;
		}
                type = methods[m].getParameterTypes()[0];
            }
        }
        
        if (convert)
        {
            if (type==null)
            {
	        LOG.warn("No mbean type for " + name+" on "+_managed.getClass());
		return null;
	    }
                
            if (type.isPrimitive() && !type.isArray())
            {
	        LOG.warn("Cannot convert mbean primative " + name);
		return null;
	    }
        }

        if (getter == null && setter == null)
        {
	    LOG.warn("No mbean getter or setters found for " + name+ " in "+oClass);
	    return null;
	}

        try
        {
            // Remember the methods
            _getters.put(name, getter);
            _setters.put(name, setter);

            MBeanAttributeInfo info=null;
            if (convert)
            {
                _convert.add(name);
                if (type.isArray())
                    info= new MBeanAttributeInfo(name,OBJECT_NAME_ARRAY_CLASS,description,getter!=null,setter!=null,getter!=null&&getter.getName().startsWith("is"));

                else
                    info= new MBeanAttributeInfo(name,OBJECT_NAME_CLASS,description,getter!=null,setter!=null,getter!=null&&getter.getName().startsWith("is"));
            }
            else
                info= new MBeanAttributeInfo(name,description,getter,setter);

            return info;
        }
        catch (Exception e)
        {
            LOG.warn(name+": "+metaData, e);
            throw new IllegalArgumentException(e.toString());
        }
    }


    /* ------------------------------------------------------------ */
    /**
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
    private MBeanOperationInfo defineOperation(String signature, String metaData, ResourceBundle bundle)
    {
        String[] tokens=metaData.split(":",3);
        int i=tokens.length-1;
        String description=tokens[i--];
        String impact_name = i<0?"UNKNOWN":tokens[i--].trim();
        if (i==0)
            tokens[0]=tokens[0].trim();
        boolean onMBean= i==0 && ("MBean".equalsIgnoreCase(tokens[0])||"MMBean".equalsIgnoreCase(tokens[0]));
        boolean convert= i==0 && ("MObject".equalsIgnoreCase(tokens[0])||"MMBean".equalsIgnoreCase(tokens[0]));

        if (LOG.isDebugEnabled())
            LOG.debug("defineOperation "+signature+" "+onMBean+":"+impact_name+":"+description);

        Class oClass = onMBean ? this.getClass() : _managed.getClass();

        try
        {
            // Resolve the impact
            int impact=MBeanOperationInfo.UNKNOWN;
            if (impact_name==null || impact_name.equals("UNKNOWN"))
                impact=MBeanOperationInfo.UNKNOWN;
            else if (impact_name.equals("ACTION"))
                impact=MBeanOperationInfo.ACTION;
            else if (impact_name.equals("INFO"))
                impact=MBeanOperationInfo.INFO;
            else if (impact_name.equals("ACTION_INFO"))
                impact=MBeanOperationInfo.ACTION_INFO;
            else
                LOG.warn("Unknown impact '"+impact_name+"' for "+signature);


            // split the signature
            String[] parts=signature.split("[\\(\\)]");
            String method_name=parts[0];
            String arguments=parts.length==2?parts[1]:null;
            String[] args=arguments==null?new String[0]:arguments.split(" *, *");

            // Check types and normalize signature.
            Class[] types = new Class[args.length];
            MBeanParameterInfo[] pInfo = new MBeanParameterInfo[args.length];
            signature=method_name;
            for (i = 0; i < args.length; i++)
            {
                Class type = TypeUtil.fromName(args[i]);
                if (type == null)
                    type = Thread.currentThread().getContextClassLoader().loadClass(args[i]);
                types[i] = type;
                args[i] = type.isPrimitive() ? TypeUtil.toName(type) : args[i];
                signature+=(i>0?",":"(")+args[i];
            }
            signature+=(i>0?")":"()");

            // Build param infos
            for (i = 0; i < args.length; i++)
            {
                String param_desc = bundle.getString(signature + "[" + i + "]");
                parts=param_desc.split(" *: *",2);
                if (LOG.isDebugEnabled())
                    LOG.debug(parts[0]+": "+parts[1]);
                pInfo[i] = new MBeanParameterInfo(parts[0].trim(), args[i], parts[1].trim());
            }

            // build the operation info
            Method method = oClass.getMethod(method_name, types);
            Class returnClass = method.getReturnType();
            _methods.put(signature, method);
            if (convert)
                _convert.add(signature);

            return new MBeanOperationInfo(method_name, description, pInfo, returnClass.isPrimitive() ? TypeUtil.toName(returnClass) : (returnClass.getName()), impact);
        }
        catch (Exception e)
        {
            LOG.warn("Operation '"+signature+"'", e);
            throw new IllegalArgumentException(e.toString());
        }

    }

}
