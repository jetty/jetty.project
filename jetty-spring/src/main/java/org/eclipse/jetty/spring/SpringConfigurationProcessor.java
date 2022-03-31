//
//  ========================================================================
//  Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
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
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.ServiceLoader;

import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.xml.ConfigurationProcessor;
import org.eclipse.jetty.xml.ConfigurationProcessorFactory;
import org.eclipse.jetty.xml.XmlConfiguration;
import org.eclipse.jetty.xml.XmlParser;
import org.springframework.beans.BeanWrapper;
import org.springframework.beans.PropertyValues;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.xml.XmlBeanDefinitionReader;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;

/**
 * Spring ConfigurationProcessor
 * <p>
 * A {@link ConfigurationProcessor} that uses a spring XML file to emulate the {@link XmlConfiguration} format.
 * <p>
 * {@link XmlConfiguration} expects a primary object that is either passed in to a call to {@link #configure(Object)}
 * or that is constructed by a call to {@link #configure()}. This processor looks for a bean definition
 * with an id, name or alias of "Main" as uses that as the primary bean.
 * <p>
 * The objects mapped by {@link XmlConfiguration#getIdMap()} are set as singletons before any configuration calls
 * and if the spring configuration file contains a definition for the singleton id, the the singleton is updated
 * with a call to {@link DefaultListableBeanFactory#configureBean(Object, String)}.
 * <p>
 * The property map obtained via {@link XmlConfiguration#getProperties()} is set as a singleton called "properties"
 * and values can be accessed by somewhat verbose
 * usage of {@link org.springframework.beans.factory.config.MethodInvokingFactoryBean}.
 * <p>
 * This processor is returned by the {@link SpringConfigurationProcessorFactory} for any XML document whos first
 * element is "beans". The factory is discovered by a {@link ServiceLoader} for {@link ConfigurationProcessorFactory}.
 * @deprecated Has been removed in Jetty 10+
 */
@Deprecated
public class SpringConfigurationProcessor implements ConfigurationProcessor
{
    private static final Logger LOG = Log.getLogger(SpringConfigurationProcessor.class);

    private XmlConfiguration _configuration;
    private DefaultListableBeanFactory _beanFactory;
    private String _main;

    public SpringConfigurationProcessor()
    {
        LOG.warn("DEPRECATION WARNING - The `jetty-spring` project will see no further updates, and has been fully removed from Jetty 10 onwards");
    }

    @Override
    public void init(URL url, XmlParser.Node root, XmlConfiguration configuration)
    {
        // Moving back and forth between URL and File/FileSystem/Path/Resource is known to cause escaping issues.
        init(org.eclipse.jetty.util.resource.Resource.newResource(url), root, configuration);
    }

    @Override
    public void init(org.eclipse.jetty.util.resource.Resource jettyResource, XmlParser.Node config, XmlConfiguration configuration)
    {
        try
        {
            _configuration = configuration;

            Resource springResource;

            if (jettyResource != null)
            {
                springResource = new UrlResource(jettyResource.getURI());
            }
            else
            {
                springResource = new ByteArrayResource((
                    "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
                        "<!DOCTYPE beans PUBLIC \"-//SPRING//DTD BEAN//EN\" \"http://www.springframework.org/dtd/spring-beans.dtd\">" +
                        config).getBytes(StandardCharsets.UTF_8));
            }

            _beanFactory = new DefaultListableBeanFactory()
            {
                @Override
                protected void applyPropertyValues(String beanName, BeanDefinition mbd, BeanWrapper bw, PropertyValues pvs)
                {
                    _configuration.initializeDefaults(bw.getWrappedInstance());
                    super.applyPropertyValues(beanName, mbd, bw, pvs);
                }
            };

            new XmlBeanDefinitionReader(_beanFactory).loadBeanDefinitions(springResource);
        }
        catch (Exception e)
        {
            throw new RuntimeException(e);
        }
    }

    @Override
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
    @Override
    public Object configure() throws Exception
    {
        doConfigure();
        return _beanFactory.getBean(_main);
    }

    private void doConfigure()
    {
        _beanFactory.registerSingleton("properties", _configuration.getProperties());

        // Look for the main bean;
        for (String bean : _beanFactory.getBeanDefinitionNames())
        {
            LOG.debug("{} - {}", bean, Arrays.asList(_beanFactory.getAliases(bean)));
            String[] aliases = _beanFactory.getAliases(bean);
            if ("Main".equals(bean) || Arrays.asList(aliases).contains("Main"))
            {
                _main = bean;
                break;
            }
        }
        if (_main == null)
            _main = _beanFactory.getBeanDefinitionNames()[0];

        // Register id beans as singletons
        Map<String, Object> idMap = _configuration.getIdMap();
        LOG.debug("idMap {}", idMap);
        for (String id : idMap.keySet())
        {
            LOG.debug("register {}", id);
            _beanFactory.registerSingleton(id, idMap.get(id));
        }

        // Apply configuration to existing singletons
        for (String id : idMap.keySet())
        {
            if (_beanFactory.containsBeanDefinition(id))
            {
                LOG.debug("reconfigure {}", id);
                _beanFactory.configureBean(idMap.get(id), id);
            }
        }

        // Extract id's for next time.
        for (String id : _beanFactory.getSingletonNames())
        {
            idMap.put(id, _beanFactory.getBean(id));
        }
    }
}
