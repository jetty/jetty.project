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

package org.eclipse.jetty.tests.distribution.session;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.MariaDBContainer;

/**
 *
 */
public class JDBCSessionDistributionTests extends AbstractSessionDistributionTests
{

    private static final Logger LOGGER = LoggerFactory.getLogger(JDBCSessionDistributionTests.class);

    private static final String MARIA_DB_USER = "beer";
    private static final String MARIA_DB_PASSWORD = "pacific_ale";
    private String jdbcUrl;
    private String driverClassName;

    private MariaDBContainer mariaDBContainer = new MariaDBContainer("mariadb:" + System.getProperty("mariadb.docker.version", "10.3.6"))
            .withUsername(MARIA_DB_USER)
            .withPassword(MARIA_DB_PASSWORD)
            .withDatabaseName("sessions");

    @Override
    public void startExternalSessionStorage() throws Exception
    {
        mariaDBContainer.start();
        jdbcUrl = mariaDBContainer.getJdbcUrl() + "?user=" + MARIA_DB_USER +
                "&password=" + MARIA_DB_PASSWORD;
        driverClassName = mariaDBContainer.getDriverClassName();

        // prepare mariadb driver mod file
        String mariaDBVersion = System.getProperty("mariadb.version");
        StringBuilder modFileContent = new StringBuilder();
        modFileContent.append("[lib]").append(System.lineSeparator());
        modFileContent.append("lib/mariadb-java-client-" + mariaDBVersion + ".jar").append(System.lineSeparator());
        modFileContent.append("[files]").append(System.lineSeparator());
        modFileContent.append("maven://org.mariadb.jdbc/mariadb-java-client/" + mariaDBVersion +
                "|lib/mariadb-java-client-" + mariaDBVersion + ".jar")
                .append(System.lineSeparator());

        Path modulesDirectory = Path.of(jettyHomeTester.getJettyBase().toString(), "modules");
        if (Files.notExists(modulesDirectory))
        {
            Files.createDirectories(modulesDirectory);
        }
        Path mariaDbModPath = Path.of(modulesDirectory.toString(), "mariadb-driver.mod");
        Files.deleteIfExists(mariaDbModPath);
        Files.createFile(mariaDbModPath);
        LOGGER.info("create file modfile: {} with content {} ", mariaDbModPath, modFileContent);
        Files.writeString(mariaDbModPath, modFileContent);
    }

    @Override
    public void stopExternalSessionStorage() throws Exception
    {
        mariaDBContainer.stop();
    }
    
    @Override
    public void configureExternalSessionStorage(Path jettyBase) throws Exception
    {
        // no op
    }

    @Override
    public List<String> getFirstStartExtraArgs()
    {
        return Arrays.asList(
                "jetty.session.jdbc.driverUrl=" + jdbcUrl,
                "db-connection-type=driver",
                "jetty.session.jdbc.driverClass=" + driverClassName
        );
    }

    @Override
    public String getFirstStartExtraModules()
    {
        return "session-store-jdbc,mariadb-driver";
    }

    @Override
    public List<String> getSecondStartExtraArgs()
    {
        return Arrays.asList(
                "jetty.session.jdbc.driverUrl=" + jdbcUrl,
                "db-connection-type=driver",
                "jetty.session.jdbc.driverClass=" + driverClassName
            );
    }

}
