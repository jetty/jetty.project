//
//  ========================================================================
//  Copyright (c) 1995-2013 Mort Bay Consulting Pty. Ltd.
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


package org.eclipse.jetty.spring;

import java.net.URL;
import java.util.Arrays;
import java.util.Map;
import java.util.ServiceLoader;

import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.xml.ConfigurationProcessor;
import org.eclipse.jetty.xml.ConfigurationProcessorFactory;
import org.eclipse.jetty.xml.XmlConfiguration;
import org.eclipse.jetty.xml.XmlParser;
import org.springframework.beans.factory.xml.XmlBeanFactory;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;

/**
 * Spring ConfigurationProcessor
 * <p/>
 * A {@link ConfigurationProcessor} that uses a spring XML file to emulate the {@link XmlConfiguration} format.
 * <p/>
 * {@link XmlConfiguration} expects a primary object that is either passed in to a call to {@link #configure(Object)}
 * or that is constructed by a call to {@link #configure()}. This processor looks for a bean definition
 * with an id, name or alias of "Main" as uses that as the primary bean.
 * <p/>
 * The objects mapped by {@link XmlConfiguration#getIdMap()} are set as singletons before any configuration calls
 * and if the spring configuration file contains a definition for the singleton id, the the singleton is updated
 * with a call to {@link XmlBeanFactory#configureBean(Object, String)}.
 * <p/>
 * The property map obtained via {@link XmlConfiguration#getProperties()} is set as a singleton called "properties"
 * and values can be accessed by somewhat verbose
 * usage of {@link org.springframework.beans.factory.config.MethodInvokingFactoryBean}.
 * <p/>
 * This processor is returned by the {@link SpringConfigurationProcessorFactory} for any XML document whos first
 * element is "beans". The factory is discovered by a {@link ServiceLoader} for {@link ConfigurationProcessorFactory}.
 */
public class SpringConfigurationProcessor implements ConfigurationProcessor
{
    private static final Logger LOG = Log.getLogger(SpringConfigurationProcessor.class);

    private Map<String, Object> _idMap;
    private Map<String, String> _propertyMap;
    private XmlBeanFactory _beanFactory;
    private String _main;

    public void init(URL url, XmlParser.Node config, Map<String, Object> idMap, Map<String, String> properties)
    {
        try
        {
            _idMap = idMap;
            _propertyMap = properties;

            Resource resource = url != null
                    ? new UrlResource(url)
                    : new ByteArrayResource(("" +
                    "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
                    "<!DOCTYPE beans PUBLIC \"-//SPRING//DTD BEAN//EN\" \"http://www.springframework.org/dtd/spring-beans.dtd\">" +
                    config).getBytes("UTF-8"));

            _beanFactory = new XmlBeanFactory(resource);
        }
        catch (Exception e)
        {
            throw new RuntimeException(e);
        }
    }

    public Object configure(Object obj) throws Exception
    {
        doConfigure();
        return _beanFactory.configureBean(obj, _main);
    }

    /**
     * Return a configured bean.  If a bean has the id or alias of "Main", then it is returned, otherwise the first bean in the file is returned.
     *
     * @see org.eclipse.jetty.xml.ConfigurationProcessor#configure()
     */
    public Object configure() throws Exception
    {
        doConfigure();
        return _beanFactory.getBean(_main);
    }

    private void doConfigure()
    {
        _beanFactory.registerSingleton("properties", _propertyMap);

        // Look for the main bean;
        for (String bean : _beanFactory.getBeanDefinitionNames())
        {
            LOG.debug("{} - {}", bean, Arrays.asList(_beanFactory.getAliases(bean)));
            String[] aliases = _beanFactory.getAliases(bean);
            if ("Main".equals(bean) || aliases != null && Arrays.asList(aliases).contains("Main"))
            {
                _main = bean;
                break;
            }
        }
        if (_main == null)
            _main = _beanFactory.getBeanDefinitionNames()[0];

        // Register id beans as singletons
        LOG.debug("idMap {}", _idMap);
        for (String id : _idMap.keySet())
        {
            LOG.debug("register {}", id);
            _beanFactory.registerSingleton(id, _idMap.get(id));
        }

        // Apply configuration to existing singletons
        for (String id : _idMap.keySet())
        {
            if (_beanFactory.containsBeanDefinition(id))
            {
                LOG.debug("reconfigure {}", id);
                _beanFactory.configureBean(_idMap.get(id), id);
            }
        }

        // Extract id's for next time.
        for (String id : _beanFactory.getSingletonNames())
            _idMap.put(id, _beanFactory.getBean(id));
    }
}
