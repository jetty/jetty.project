//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.plus.webapp;

import java.util.Iterator;
import java.util.Objects;
import javax.naming.Context;
import javax.naming.InitialContext;
import javax.naming.NameNotFoundException;

import org.eclipse.jetty.jndi.NamingUtil;
import org.eclipse.jetty.plus.annotation.Injection;
import org.eclipse.jetty.plus.annotation.InjectionCollection;
import org.eclipse.jetty.plus.annotation.LifeCycleCallback;
import org.eclipse.jetty.plus.annotation.LifeCycleCallbackCollection;
import org.eclipse.jetty.plus.annotation.PostConstructCallback;
import org.eclipse.jetty.plus.annotation.PreDestroyCallback;
import org.eclipse.jetty.plus.jndi.EnvEntry;
import org.eclipse.jetty.plus.jndi.Link;
import org.eclipse.jetty.plus.jndi.NamingEntry;
import org.eclipse.jetty.plus.jndi.NamingEntryUtil;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.TypeUtil;
import org.eclipse.jetty.webapp.Descriptor;
import org.eclipse.jetty.webapp.FragmentDescriptor;
import org.eclipse.jetty.webapp.IterativeDescriptorProcessor;
import org.eclipse.jetty.webapp.Origin;
import org.eclipse.jetty.webapp.WebAppContext;
import org.eclipse.jetty.xml.XmlParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * PlusDescriptorProcessor
 */
public class PlusDescriptorProcessor extends IterativeDescriptorProcessor
{
    private static final Logger LOG = LoggerFactory.getLogger(PlusDescriptorProcessor.class);

    public PlusDescriptorProcessor()
    {
        try
        {
            registerVisitor("env-entry", getClass().getMethod("visitEnvEntry", __signature));
            registerVisitor("resource-ref", getClass().getMethod("visitResourceRef", __signature));
            registerVisitor("resource-env-ref", getClass().getMethod("visitResourceEnvRef", __signature));
            registerVisitor("message-destination-ref", getClass().getMethod("visitMessageDestinationRef", __signature));
            registerVisitor("post-construct", getClass().getMethod("visitPostConstruct", __signature));
            registerVisitor("pre-destroy", getClass().getMethod("visitPreDestroy", __signature));
        }
        catch (Exception e)
        {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void start(WebAppContext context, Descriptor descriptor)
    {

        InjectionCollection injections = (InjectionCollection)context.getAttribute(InjectionCollection.INJECTION_COLLECTION);
        if (injections == null)
        {
            injections = new InjectionCollection();
            context.setAttribute(InjectionCollection.INJECTION_COLLECTION, injections);
        }

        LifeCycleCallbackCollection callbacks = (LifeCycleCallbackCollection)context.getAttribute(LifeCycleCallbackCollection.LIFECYCLE_CALLBACK_COLLECTION);
        if (callbacks == null)
        {
            callbacks = new LifeCycleCallbackCollection();
            context.setAttribute(LifeCycleCallbackCollection.LIFECYCLE_CALLBACK_COLLECTION, callbacks);
        }
    }

    @Override
    public void end(WebAppContext context, Descriptor descriptor)
    {
    }

    /**
     * JavaEE 5.4.1.3
     *
     * @param context the context
     * @param descriptor the descriptor
     * @param node the xml node
     * @throws Exception if unable to process jndi bindings
     */
    public void visitEnvEntry(WebAppContext context, Descriptor descriptor, XmlParser.Node node)
        throws Exception
    {
        String name = node.getString("env-entry-name", false, true);
        String type = node.getString("env-entry-type", false, true);
        String valueStr = node.getString("env-entry-value", false, true);

        Origin o = context.getMetaData().getOrigin("env-entry." + name);
        switch (o)
        {
            case NotSet:
            {
                //no descriptor has configured an env-entry of this name previously
                context.getMetaData().setOrigin("env-entry." + name, descriptor);
                makeEnvEntryInjectionsAndBindings(context, descriptor, node, name, type, valueStr);        
                break;
            }
            case WebXml:
            case WebDefaults:
            case WebOverride:
            {
                //ServletSpec 3.0 p75. web.xml (or web-override/web-defaults) declared
                //the env-entry. A fragment is not allowed to change that, except unless
                //the web.xml did not declare any injections.
                if (!(descriptor instanceof FragmentDescriptor))
                {
                    //We're processing web-defaults, web.xml or web-override. Any of them can
                    //set or change the env-entry.
                    context.getMetaData().setOrigin("env-entry." + name, descriptor);
                    makeEnvEntryInjectionsAndBindings(context, descriptor, node, name, type, valueStr);
                }
                else
                {
                    //A web.xml declared the env-entry. Check to see if any injections have been
                    //declared for it. If it was declared in web.xml then don't merge any injections.
                    //If it was declared in a web-fragment, then we can keep merging fragments.
                    Descriptor d = context.getMetaData().getOriginDescriptor("env-entry." + name + ".injection");
                    if (d == null || d instanceof FragmentDescriptor)
                        addInjections(context, descriptor, node, name, TypeUtil.fromName(type));
                }
                break;
            }
            case WebFragment:
            {
                //ServletSpec p.75. No declaration in web.xml, but in multiple web-fragments. Error.
                throw new IllegalStateException("Conflicting env-entry " + name + " in " + descriptor.getResource());
            }
            default:
                break;
        }
    }

    /**
     * Common Annotations Spec section 2.3:
     * <p>
     * resource-ref is for:
     * <ul>
     * <li>javax.sql.DataSource</li>
     * <li>javax.jms.ConnectionFactory</li>
     * <li>javax.jms.QueueConnectionFactory</li>
     * <li>javax.jms.TopicConnectionFactory</li>
     * <li>jakarta.mail.Session</li>
     * <li>java.net.URL</li>
     * <li>javax.resource.cci.ConnectionFactory</li>
     * <li>org.omg.CORBA_2_3.ORB</li>
     * <li>any other connection factory defined by a resource adapter</li>
     * </ul>
     *
     * If web.xml contains a resource-ref with injection targets, all resource-ref entries
     * of the same name are ignored in web fragments. If web.xml does not contain any
     * injection-targets, then they are merged from all the fragments.
     * If web.xml does not contain a resource-ref element of same name, but 2 fragments
     * declare the same name it is an error.
     * resource-ref entries are ONLY for connection factories
     * the resource-ref says how the app will reference the jndi lookup relative
     * to java:comp/env, but it is up to the deployer to map this reference to
     * a real resource in the environment. At the moment, we insist that the
     * jetty.xml file name of the resource has to be exactly the same as the
     * name in web.xml deployment descriptor, but it shouldn't have to be
     *
     * <p>
     * Maintenance update 3.0a to spec:
     * <p>
     * Update Section 8.2.3.h.ii with the following -  If a resource reference
     * element is specified in two fragments, while absent from the main web.xml,
     * and all the attributes and child elements of the resource reference element
     * are identical, the resource reference will be merged  into the main web.xml.
     * It is considered an error if a resource reference element has the same name
     * specified in two fragments, while absent from the main web.xml and the attributes
     * and child elements are not identical in the two fragments. For example, if two
     * web fragments declare a <code>&lt;resource-ref&gt;</code> with the same <code>&lt;resource-ref-name&gt;</code> element
     * but the type in one is specified as javax.sql.DataSource while the type in the
     * other is that of a java mail resource, then an error must be reported and the
     * application MUST fail to deploy.
     *
     * @param context the context
     * @param descriptor the descriptor
     * @param node the xml node
     * @throws Exception if unable to bind nodes, or load classes
     */
    public void visitResourceRef(WebAppContext context, Descriptor descriptor, XmlParser.Node node)
        throws Exception
    {
        String jndiName = node.getString("res-ref-name", false, true);
        String type = node.getString("res-type", false, true);
        String auth = node.getString("res-auth", false, true);
        String shared = node.getString("res-sharing-scope", false, true);

        Origin o = context.getMetaData().getOrigin("resource-ref." + jndiName);
        switch (o)
        {
            case NotSet:
            {
                //No descriptor or annotation previously declared a resource-ref of this name.
                context.getMetaData().setOrigin("resource-ref." + jndiName, descriptor);

                //check for <injection> elements
                Class<?> typeClass = TypeUtil.fromName(type);
                if (typeClass == null)
                    typeClass = context.loadClass(type);
                addInjections(context, descriptor, node, jndiName, typeClass);
                bindResourceRef(context, jndiName, typeClass);
                break;
            }
            case WebXml:
            case WebDefaults:
            case WebOverride:
            {
                //A web xml previously declared the resource-ref.
                if (!(descriptor instanceof FragmentDescriptor))
                {
                    //We're processing web-defaults, web.xml or web-override. Any of them can
                    //set or change the resource-ref.
                    context.getMetaData().setOrigin("resource-ref." + jndiName, descriptor);

                    //check for <injection> elements
                    Class<?> typeClass = TypeUtil.fromName(type);
                    if (typeClass == null)
                        typeClass = context.loadClass(type);

                    addInjections(context, descriptor, node, jndiName, typeClass);

                    //bind the entry into jndi
                    bindResourceRef(context, jndiName, typeClass);
                }
                else
                {
                    //A web xml declared the resource-ref and we're processing a
                    //web-fragment. Check to see if any injections were declared for it by web.xml.
                    //If any injection was declared in web.xml then don't merge any injections.
                    //If it was declared in a web-fragment, then we can keep merging fragments.
                    Descriptor d = context.getMetaData().getOriginDescriptor("resource-ref." + jndiName + ".injection");
                    if (d == null || d instanceof FragmentDescriptor)
                    {
                        Class<?> typeClass = TypeUtil.fromName(type);
                        if (typeClass == null)
                            typeClass = context.loadClass(type);
                        addInjections(context, descriptor, node, jndiName, TypeUtil.fromName(type));
                    }
                }
                break;
            }
            case WebFragment:
            {
                Descriptor otherFragment = context.getMetaData().getOriginDescriptor("resource-ref." + jndiName);
                XmlParser.Node otherFragmentRoot = otherFragment.getRoot();
                Iterator<Object> iter = otherFragmentRoot.iterator();
                XmlParser.Node otherNode = null;
                while (iter.hasNext() && otherNode == null)
                {
                    Object obj = iter.next();
                    if (!(obj instanceof XmlParser.Node))
                        continue;
                    XmlParser.Node n = (XmlParser.Node)obj;
                    if ("resource-ref".equals(n.getTag()) && jndiName.equals(n.getString("res-ref-name", false, true)))
                        otherNode = n;
                }

                //If declared in another web-fragment
                if (otherNode != null)
                {
                    //declarations of the resource-ref must be the same in both fragment descriptors
                    String otherType = otherNode.getString("res-type", false, true);
                    otherType = (otherType == null ? "" : otherType);
                    String otherAuth = otherNode.getString("res-auth", false, true);
                    otherAuth = (otherAuth == null ? "" : otherAuth);
                    String otherShared = otherNode.getString("res-sharing-scope", false, true);
                    otherShared = (otherShared == null ? "" : otherShared);

                    //otherType, otherAuth and otherShared must be the same as type, auth, shared
                    type = (type == null ? "" : type);
                    auth = (auth == null ? "" : auth);
                    shared = (shared == null ? "" : shared);

                    //ServletSpec p.75. No declaration of resource-ref in web xml, but different in multiple web-fragments. Error.
                    if (!type.equals(otherType) || !auth.equals(otherAuth) || !shared.equals(otherShared))
                        throw new IllegalStateException("Conflicting resource-ref " + jndiName + " in " + descriptor.getResource());
                    //same in multiple web-fragments, merge the injections
                    addInjections(context, descriptor, node, jndiName, TypeUtil.fromName(type));
                }
                else
                    throw new IllegalStateException("resource-ref." + jndiName + " not found in declaring descriptor " + otherFragment);
                break;
            }

            default:
                break;
        }
    }

    /**
     * Common Annotations Spec section 2.3:
     * <p>
     * resource-env-ref is for:
     * <ul>
     * <li>javax.transaction.UserTransaction</li>
     * <li>javax.resource.cci.InteractionSpec</li>
     * <li>anything else that is not a connection factory</li>
     * </ul>
     *
     * @param context the context
     * @param descriptor the descriptor
     * @param node the xml node
     * @throws Exception if unable to load classes, or bind jndi entries
     */
    public void visitResourceEnvRef(WebAppContext context, Descriptor descriptor, XmlParser.Node node)
        throws Exception
    {
        String jndiName = node.getString("resource-env-ref-name", false, true);
        String type = node.getString("resource-env-ref-type", false, true);

        Origin o = context.getMetaData().getOrigin("resource-env-ref." + jndiName);
        switch (o)
        {
            case NotSet:
            {
                //First declaration of resource-env-ref with this jndiName
                //JavaEE Spec sec 5.7.1.3 says the resource-env-ref-type
                //is mandatory, but the schema says it is optional!
                Class<?> typeClass = TypeUtil.fromName(type);
                if (typeClass == null)
                    typeClass = context.loadClass(type);
                addInjections(context, descriptor, node, jndiName, typeClass);
                bindResourceEnvRef(context, jndiName, typeClass);
                break;
            }
            case WebXml:
            case WebDefaults:
            case WebOverride:
            {
                //A resource-env-ref of this name has been declared first in a web xml.
                //Only allow other web-default, web.xml, web-override to change it.
                if (!(descriptor instanceof FragmentDescriptor))
                {
                    //We're processing web-defaults, web.xml or web-override. Any of them can
                    //set or change the resource-env-ref.
                    context.getMetaData().setOrigin("resource-env-ref." + jndiName, descriptor);
                    Class<?> typeClass = TypeUtil.fromName(type);
                    if (typeClass == null)
                        typeClass = context.loadClass(type);
                    addInjections(context, descriptor, node, jndiName, typeClass);
                    bindResourceEnvRef(context, jndiName, typeClass);
                }
                else
                {
                    //We're processing a web-fragment. It can only contribute injections if the
                    //there haven't been any injections declared yet, or they weren't declared in a WebXml file.
                    Descriptor d = context.getMetaData().getOriginDescriptor("resource-env-ref." + jndiName + ".injection");
                    if (d == null || d instanceof FragmentDescriptor)
                    {
                        Class<?> typeClass = TypeUtil.fromName(type);
                        if (typeClass == null)
                            typeClass = context.loadClass(type);
                        addInjections(context, descriptor, node, jndiName, typeClass);
                    }
                }
                break;
            }
            case WebFragment:
            {
                Descriptor otherFragment = context.getMetaData().getOriginDescriptor("resource-env-ref." + jndiName);
                XmlParser.Node otherFragmentRoot = otherFragment.getRoot();
                Iterator<Object> iter = otherFragmentRoot.iterator();
                XmlParser.Node otherNode = null;
                while (iter.hasNext() && otherNode == null)
                {
                    Object obj = iter.next();
                    if (!(obj instanceof XmlParser.Node))
                        continue;
                    XmlParser.Node n = (XmlParser.Node)obj;
                    if ("resource-env-ref".equals(n.getTag()) && jndiName.equals(n.getString("resource-env-ref-name", false, true)))
                        otherNode = n;
                }
                if (otherNode != null)
                {
                    //declarations of the resource-ref must be the same in both fragment descriptors
                    String otherType = otherNode.getString("resource-env-ref-type", false, true);

                    //types must be the same
                    type = (type == null ? "" : type);
                    otherType = (otherType == null ? "" : otherType);

                    //ServletSpec p.75. No declaration of resource-ref in web xml, but different in multiple web-fragments. Error.
                    if (!type.equals(otherType))
                        throw new IllegalStateException("Conflicting resource-env-ref " + jndiName + " in " + descriptor.getResource());

                    //same in multiple web-fragments, merge the injections
                    addInjections(context, descriptor, node, jndiName, TypeUtil.fromName(type));
                }
                else
                    throw new IllegalStateException("resource-env-ref." + jndiName + " not found in declaring descriptor " + otherFragment);
                break;
            }
            default:
                break;
        }
    }

    /**
     * Common Annotations Spec section 2.3:
     * <p>
     * message-destination-ref is for:
     * <ul>
     * <li>javax.jms.Queue</li>
     * <li>javax.jms.Topic</li>
     * </ul>
     *
     * @param context the context
     * @param descriptor the descriptor
     * @param node the xml node
     * @throws Exception if unable to load classes or bind jndi entries
     */
    public void visitMessageDestinationRef(WebAppContext context, Descriptor descriptor, XmlParser.Node node)
        throws Exception
    {
        String jndiName = node.getString("message-destination-ref-name", false, true);
        String type = node.getString("message-destination-type", false, true);
        String usage = node.getString("message-destination-usage", false, true);

        Origin o = context.getMetaData().getOrigin("message-destination-ref." + jndiName);
        switch (o)
        {
            case NotSet:
            {
                //A message-destination-ref of this name has not been previously declared
                Class<?> typeClass = TypeUtil.fromName(type);
                if (typeClass == null)
                    typeClass = context.loadClass(type);
                addInjections(context, descriptor, node, jndiName, typeClass);
                bindMessageDestinationRef(context, jndiName, typeClass);
                context.getMetaData().setOrigin("message-destination-ref." + jndiName, descriptor);
                break;
            }
            case WebXml:
            case WebDefaults:
            case WebOverride:
            {
                //A message-destination-ref of this name has been declared first in a web xml.
                //Only allow other web-default, web.xml, web-override to change it.
                if (!(descriptor instanceof FragmentDescriptor))
                {
                    Class<?> typeClass = TypeUtil.fromName(type);
                    if (typeClass == null)
                        typeClass = context.loadClass(type);
                    addInjections(context, descriptor, node, jndiName, typeClass);
                    bindMessageDestinationRef(context, jndiName, typeClass);
                    context.getMetaData().setOrigin("message-destination-ref." + jndiName, descriptor);
                }
                else
                {
                    //A web-fragment has declared a message-destination-ref with the same name as a web xml.
                    //It can only contribute injections, and only if the web xml didn't declare any.
                    Descriptor d = context.getMetaData().getOriginDescriptor("message-destination-ref." + jndiName + ".injection");
                    if (d == null || d instanceof FragmentDescriptor)
                    {
                        Class<?> typeClass = TypeUtil.fromName(type);
                        if (typeClass == null)
                            typeClass = context.loadClass(type);
                        addInjections(context, descriptor, node, jndiName, typeClass);
                    }
                }
                break;
            }
            case WebFragment:
            {
                Descriptor otherFragment = context.getMetaData().getOriginDescriptor("message-destination-ref." + jndiName);
                XmlParser.Node otherFragmentRoot = otherFragment.getRoot();
                Iterator<Object> iter = otherFragmentRoot.iterator();
                XmlParser.Node otherNode = null;
                while (iter.hasNext() && otherNode == null)
                {
                    Object obj = iter.next();
                    if (!(obj instanceof XmlParser.Node))
                        continue;
                    XmlParser.Node n = (XmlParser.Node)obj;
                    if ("message-destination-ref".equals(n.getTag()) && jndiName.equals(n.getString("message-destination-ref-name", false, true)))
                        otherNode = n;
                }
                if (otherNode != null)
                {
                    String otherType = node.getString("message-destination-type", false, true);
                    String otherUsage = node.getString("message-destination-usage", false, true);

                    type = (type == null ? "" : type);
                    usage = (usage == null ? "" : usage);
                    if (!type.equals(otherType) || !usage.equalsIgnoreCase(otherUsage))
                        throw new IllegalStateException("Conflicting message-destination-ref " + jndiName + " in " + descriptor.getResource());

                    //same in multiple web-fragments, merge the injections
                    addInjections(context, descriptor, node, jndiName, TypeUtil.fromName(type));
                }
                else
                    throw new IllegalStateException("message-destination-ref." + jndiName + " not found in declaring descriptor " + otherFragment);
                break;
            }
            default:
                break;
        }
    }

    /**
     * If web.xml has at least 1 post-construct, then all post-constructs in fragments
     * are ignored. Otherwise, post-constructs from fragments are merged.
     * post-construct is the name of a class and method to call after all
     * resources have been setup but before the class is put into use
     *
     * @param context the context
     * @param descriptor the descriptor
     * @param node the xml node
     */
    public void visitPostConstruct(WebAppContext context, Descriptor descriptor, XmlParser.Node node)
    {
        String className = node.getString("lifecycle-callback-class", false, true);
        String methodName = node.getString("lifecycle-callback-method", false, true);

        if (className == null || className.equals(""))
        {
            LOG.warn("No lifecycle-callback-class specified");
            return;
        }
        if (methodName == null || methodName.equals(""))
        {
            LOG.warn("No lifecycle-callback-method specified for class {}", className);
            return;
        }

        //ServletSpec 3.0 p80 If web.xml declares a post-construct then all post-constructs
        //in fragments must be ignored. Otherwise, they are additive.
        Origin o = context.getMetaData().getOrigin("post-construct");
        switch (o)
        {
            case NotSet:
            {
                //No post-constructs have been declared previously.
                context.getMetaData().setOrigin("post-construct", descriptor);

                LifeCycleCallback callback = new PostConstructCallback(className, methodName);
                ((LifeCycleCallbackCollection)context.getAttribute(LifeCycleCallbackCollection.LIFECYCLE_CALLBACK_COLLECTION)).add(callback);
                break;
            }
            case WebXml:
            case WebDefaults:
            case WebOverride:
            {
                //A web xml first declared a post-construct. Only allow other web xml files (web-defaults, web-overrides etc)
                //to add to it
                if (!(descriptor instanceof FragmentDescriptor))
                {
                    try
                    {
                        Class<?> clazz = context.loadClass(className);
                        LifeCycleCallback callback = new PostConstructCallback(clazz, methodName);
                        ((LifeCycleCallbackCollection)context.getAttribute(LifeCycleCallbackCollection.LIFECYCLE_CALLBACK_COLLECTION)).add(callback);
                    }
                    catch (ClassNotFoundException e)
                    {
                        LOG.warn("Couldn't load post-construct target class {}", className);
                    }
                }
                break;
            }
            case WebFragment:
            {
                //A web-fragment first declared a post-construct. Allow all other web-fragments to merge in their post-constructs
                try
                {
                    Class<?> clazz = context.loadClass(className);
                    LifeCycleCallback callback = new PostConstructCallback(clazz, methodName);
                    ((LifeCycleCallbackCollection)context.getAttribute(LifeCycleCallbackCollection.LIFECYCLE_CALLBACK_COLLECTION)).add(callback);
                }
                catch (ClassNotFoundException e)
                {
                    LOG.warn("Couldn't load post-construct target class {}", className);
                }
                break;
            }
            default:
                break;
        }
    }

    /**
     * pre-destroy is the name of a class and method to call just as
     * the instance is being destroyed
     *
     * @param context the context
     * @param descriptor the descriptor
     * @param node the xml node
     */
    public void visitPreDestroy(WebAppContext context, Descriptor descriptor, XmlParser.Node node)
    {
        String className = node.getString("lifecycle-callback-class", false, true);
        String methodName = node.getString("lifecycle-callback-method", false, true);
        if (className == null || className.equals(""))
        {
            LOG.warn("No lifecycle-callback-class specified for pre-destroy");
            return;
        }
        if (methodName == null || methodName.equals(""))
        {
            LOG.warn("No lifecycle-callback-method specified for pre-destroy class {}", className);
            return;
        }

        Origin o = context.getMetaData().getOrigin("pre-destroy");
        switch (o)
        {
            case NotSet:
            {
                //No pre-destroys have been declared previously. Record this descriptor
                //as the first declarer.
                context.getMetaData().setOrigin("pre-destroy", descriptor);

                LifeCycleCallback callback = new PreDestroyCallback(className, methodName);
                ((LifeCycleCallbackCollection)context.getAttribute(LifeCycleCallbackCollection.LIFECYCLE_CALLBACK_COLLECTION)).add(callback);

                break;
            }
            case WebXml:
            case WebDefaults:
            case WebOverride:
            {
                //A web xml file previously declared a pre-destroy. Only allow other web xml files
                //(not web-fragments) to add to them.
                if (!(descriptor instanceof FragmentDescriptor))
                {
                    try
                    {
                        Class<?> clazz = context.loadClass(className);
                        LifeCycleCallback callback = new PreDestroyCallback(clazz, methodName);
                        ((LifeCycleCallbackCollection)context.getAttribute(LifeCycleCallbackCollection.LIFECYCLE_CALLBACK_COLLECTION)).add(callback);
                    }
                    catch (ClassNotFoundException e)
                    {
                        LOG.warn("Couldn't load pre-destroy target class {}", className);
                    }
                }
                break;
            }
            case WebFragment:
            {
                //No pre-destroys in web xml, so allow all fragments to merge their pre-destroys.
                try
                {
                    Class<?> clazz = context.loadClass(className);
                    LifeCycleCallback callback = new PreDestroyCallback(clazz, methodName);
                    ((LifeCycleCallbackCollection)context.getAttribute(LifeCycleCallbackCollection.LIFECYCLE_CALLBACK_COLLECTION)).add(callback);
                }
                catch (ClassNotFoundException e)
                {
                    LOG.warn("Couldn't load pre-destroy target class {}", className);
                }
                break;
            }
            default:
                break;
        }
    }

    /**
     * Iterate over the <code>&lt;injection-target&gt;</code> entries for a node
     *
     * @param context the context
     * @param descriptor the descriptor
     * @param node the xml node
     * @param jndiName the jndi name
     * @param valueClass the value class
     */
    public void addInjections(WebAppContext context, Descriptor descriptor, XmlParser.Node node, String jndiName, Class<?> valueClass)
    {
        Objects.requireNonNull(context);
        Objects.requireNonNull(node);
        
        Iterator<XmlParser.Node> itor = node.iterator("injection-target");

        while (itor.hasNext())
        {
            XmlParser.Node injectionNode = itor.next();
            String targetClassName = injectionNode.getString("injection-target-class", false, true);
            String targetName = injectionNode.getString("injection-target-name", false, true);
            if ((targetClassName == null) || targetClassName.equals(""))
            {
                LOG.warn("No classname found in injection-target");
                continue;
            }
            if ((targetName == null) || targetName.equals(""))
            {
                LOG.warn("No field or method name in injection-target");
                continue;
            }

            InjectionCollection injections = (InjectionCollection)context.getAttribute(InjectionCollection.INJECTION_COLLECTION);
            if (injections == null)
            {
                injections = new InjectionCollection();
                context.setAttribute(InjectionCollection.INJECTION_COLLECTION, injections);
            }
            // comments in the javaee_5.xsd file specify that the targetName is looked
            // for first as a java bean property, then if that fails, as a field
            try
            {
                Class<?> clazz = context.loadClass(targetClassName);
                Injection injection = new Injection(clazz, targetName, valueClass, jndiName, null);
                injections.add(injection);

                //Record which was the first descriptor to declare an injection for this name
                String name = node.getTag() + "." + jndiName + ".injection";
                if (context.getMetaData().getOriginDescriptor(name) == null)
                    context.getMetaData().setOrigin(name, descriptor);
            }
            catch (ClassNotFoundException e)
            {
                LOG.warn("Couldn't load injection target class {}", targetClassName);
            }
        }
    }

    /**
     * @param name the jndi name
     * @param value the value
     * @throws Exception if unable to bind entry
     */
    public void bindEnvEntry(String name, Object value) throws Exception
    {
        InitialContext ic = new InitialContext();
        Context envCtx = (Context)ic.lookup("java:comp/env");
        NamingUtil.bind(envCtx, name, value);
    }
    
    /**
     * Make injections and any java:comp/env bindings necessary given an env-entry declaration.
     * The handling of env-entries is different to other resource declarations like resource-ref, resource-env-ref etc
     * because we allow the EnvEntry (@see org.eclipse.jetty.plus.jndi.EnvEntry) class that is configured externally to the webapp 
     * to specify a value that can override a value present in a web.xml descriptor.
     * 
     * @param context the WebAppContext of the env-entry
     * @param descriptor the web.xml, web-default.xml, web-override.xml or web-fragment.xml
     * @param node the parsed xml representation of the env-entry declaration
     * @param name the name field of the env-entry
     * @param type the type field of the env-entry
     * @param value the value field of the env-entry
     * @throws Exception
     */
    public void makeEnvEntryInjectionsAndBindings(WebAppContext context, Descriptor descriptor, XmlParser.Node node, String name, String type, String value) throws Exception
    {
        InitialContext ic = new InitialContext();
        try
        {
            EnvEntry envEntry = (EnvEntry)ic.lookup("java:comp/env/" + NamingEntryUtil.makeNamingEntryName(ic.getNameParser(""), name));

            if (StringUtil.isEmpty(value))
            {
                //There is an empty or missing value in the env-entry:
                //If there is an existing EnvEntry (eg from a declaration in jetty-env.xml) that is override=true, then
                //we make the injection, but we can skip the rebinding becase we want to use the value already bound.
                //If there isn't an existing EnvEntry then there is nothing to do: according to the spec we do not make
                //an injection if the env-entry value is missing, and of course there is no value to rebind.
                if (envEntry != null && envEntry.isOverrideWebXml())
                    addInjections(context, descriptor, node, name, TypeUtil.fromName(type));
            }
            else
            {
                //There is a value for the env-entry:
                //According to the spec, we need to make an injection (if one is present).
                //If there is an existing EnvEntry(eg from a declaration in jetty-env.xml) that is override=false, then
                //we need to rebind name with the value from web.xml.
                addInjections(context, descriptor, node, name, TypeUtil.fromName(type));

                if (envEntry == null || !envEntry.isOverrideWebXml())
                    bindEnvEntry(name, TypeUtil.valueOf(type, value));
            }
        }
        catch (NameNotFoundException e)
        {
            //No matching EnvEntry has previously been bound so make the injection and do the binding with the value from web.xml
            addInjections(context, descriptor, node, name, TypeUtil.fromName(type));
            bindEnvEntry(name, TypeUtil.valueOf(type, value));
        }
    }

    /**
     * Bind a resource reference.
     * <p>
     * If a resource reference with the same name is in a jetty-env.xml
     * file, it will already have been bound.
     *
     * @param context the context
     * @param name the jndi name
     * @param typeClass the type class
     * @throws Exception if unable to bind resource
     */
    public void bindResourceRef(WebAppContext context, String name, Class<?> typeClass)
        throws Exception
    {
        bindEntry(context, name, typeClass);
    }

    public void bindResourceEnvRef(WebAppContext context, String name, Class<?> typeClass)
        throws Exception
    {
        bindEntry(context, name, typeClass);
    }

    public void bindMessageDestinationRef(WebAppContext context, String name, Class<?> typeClass)
        throws Exception
    {
        bindEntry(context, name, typeClass);
    }

    /**
     * Bind a resource with the given name from web.xml of the given type
     * with a jndi resource from either the server or the webapp's naming
     * environment.
     * <p>
     * As the servlet spec does not cover the mapping of names in web.xml with
     * names from the execution environment, jetty uses the concept of a Link, which is
     * a subclass of the NamingEntry class. A Link defines a mapping of a name
     * from web.xml with a name from the execution environment (ie either the server or the
     * webapp's naming environment).
     *
     * @param context the context
     * @param name name of the resource from web.xml
     * @param typeClass the type class
     * @throws Exception the exception
     */
    protected void bindEntry(WebAppContext context, String name, Class<?> typeClass)
        throws Exception
    {
        String nameInEnvironment = name;
        boolean bound = false;

        //check if the name in web.xml has been mapped to something else
        //check a context-specific naming environment first
        Object scope = context;
        NamingEntry ne = NamingEntryUtil.lookupNamingEntry(scope, name);

        if (ne != null && (ne instanceof Link))
        {
            //if we found a mapping, get out name it is mapped to in the environment
            nameInEnvironment = ((Link)ne).getLink();
        }

        //try finding that mapped name in the webapp's environment first
        scope = context;
        bound = NamingEntryUtil.bindToENC(scope, name, nameInEnvironment);

        if (bound)
            return;

        //try the server's environment
        scope = context.getServer();
        bound = NamingEntryUtil.bindToENC(scope, name, nameInEnvironment);
        if (bound)
            return;

        //try the jvm environment
        bound = NamingEntryUtil.bindToENC(null, name, nameInEnvironment);
        if (bound)
            return;

        //There is no matching resource so try a default name.
        //The default name syntax is: the [res-type]/default
        //eg       javax.sql.DataSource/default
        nameInEnvironment = typeClass.getName() + "/default";
        //First try the server scope
        NamingEntry defaultNE = NamingEntryUtil.lookupNamingEntry(context.getServer(), nameInEnvironment);
        if (defaultNE == null)
            defaultNE = NamingEntryUtil.lookupNamingEntry(null, nameInEnvironment);

        if (defaultNE != null)
            defaultNE.bindToENC(name);
        else
            throw new IllegalStateException("Nothing to bind for name " + name);
    }
}
