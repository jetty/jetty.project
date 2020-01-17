//
// ========================================================================
// Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under
// the terms of the Eclipse Public License 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0
//
// This Source Code may also be made available under the following
// Secondary Licenses when the conditions for such availability set
// forth in the Eclipse Public License, v. 2.0 are satisfied:
// the Apache License v2.0 which is available at
// https://www.apache.org/licenses/LICENSE-2.0
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package test;

import java.io.InputStream;
import java.util.Properties;
import javax.annotation.PostConstruct;
import javax.annotation.Resource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.support.SpringBootServletInitializer;
import org.springframework.context.annotation.Bean;

/**
 * Hello world!
 */
public class App extends SpringBootServletInitializer
{

    private Logger logger = LoggerFactory.getLogger(getClass());

    @Resource(name = "my.properties")
    private Properties somePropertyFile;

    @Override
    protected SpringApplicationBuilder configure(SpringApplicationBuilder builder)
    {
        return builder.sources(App.class);
    }

    @PostConstruct
    public void done()
    {
        logger.info("all good guys get a good {}", somePropertyFile.get("drink"));
    }

    @Bean(name = "my.properties")
    public Properties getSomeProperties() throws Exception
    {
        Properties properties = new Properties();
        try (InputStream inputStream = Thread.currentThread().getContextClassLoader().getResourceAsStream("my.properties"))
        {
            properties.load(inputStream);
        }
        return properties;
    }
}
