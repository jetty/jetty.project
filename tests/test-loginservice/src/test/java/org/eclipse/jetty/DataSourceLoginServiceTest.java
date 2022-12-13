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

package org.eclipse.jetty;

import java.io.File;
import java.io.FileOutputStream;
import java.net.URI;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.AuthenticationStore;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.util.BasicAuthentication;
import org.eclipse.jetty.plus.security.DataSourceLoginService;
import org.eclipse.jetty.toolchain.test.FS;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.util.Loader;
import org.eclipse.jetty.util.NanoTime;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mariadb.jdbc.MariaDbDataSource;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * DataSourceLoginServiceTest
 */
@Testcontainers(disabledWithoutDocker = true)
public class DataSourceLoginServiceTest
{
    private static final String _content = "This is some protected content";
    private static String REALM_NAME = "DSRealm";
    private static File __docRoot;
    private static URI __baseUri;
    private static DatabaseLoginServiceTestServer __testServer;
    private AuthenticationStore _authStore;
    private HttpClient _client;
    
    @BeforeAll
    public static void setUp() throws Exception
    {
        __docRoot = MavenTestingUtils.getTargetTestingDir("dsloginservice-test");
        FS.ensureDirExists(__docRoot);

        File content = new File(__docRoot, "input.txt");
        try (FileOutputStream out = new FileOutputStream(content))
        {
            out.write(_content.getBytes("utf-8"));
        }

        //create a datasource and bind to jndi
        MariaDbDataSource ds = new MariaDbDataSource();
        ds.setUser(DatabaseLoginServiceTestServer.MARIA_DB_USER);
        ds.setPassword(DatabaseLoginServiceTestServer.MARIA_DB_PASSWORD);
        ds.setUrl(DatabaseLoginServiceTestServer.MARIA_DB_FULL_URL);
        org.eclipse.jetty.plus.jndi.Resource binding = 
            new org.eclipse.jetty.plus.jndi.Resource(null, "dstest", ds);
        
        __testServer = new DatabaseLoginServiceTestServer();
        
        DataSourceLoginService loginService = new DataSourceLoginService();
        loginService.setUserTableName("users");
        loginService.setUserTableKey("id");
        loginService.setUserTableUserField("username");
        loginService.setUserTablePasswordField("pwd");
        loginService.setRoleTableName("roles");
        loginService.setRoleTableKey("id");
        loginService.setRoleTableRoleField("role");
        loginService.setUserRoleTableName("user_roles");
        loginService.setUserRoleTableRoleKey("role_id");
        loginService.setUserRoleTableUserKey("user_id");
        loginService.setJndiName("dstest");
        loginService.setName(REALM_NAME);
        loginService.setServer(__testServer.getServer());
        
        __testServer.setResourceBase(__docRoot.getAbsolutePath()); 
        __testServer.setLoginService(loginService);
        __testServer.start();
        __baseUri = __testServer.getBaseUri();
    }

    @AfterAll
    public static void tearDown()
        throws Exception
    {
        if (__testServer != null)
        {
            __testServer.stop();
            __testServer = null;
        }
    }

    @BeforeEach
    public void setupClient() throws Exception
    {
        _client = new HttpClient();
        _authStore = _client.getAuthenticationStore();
    }
    
    @AfterEach
    public void stopClient() throws Exception
    {
        if (_client != null)
        {
            _client.stop();
            _client = null;
        }
    }

    @Test
    public void testGetAndPasswordUpdate() throws Exception
    {
        try
        {
            _authStore.addAuthentication(new BasicAuthentication(__baseUri, REALM_NAME, "dstest", "dstest"));
            _client.start();
            ContentResponse response = _client.GET(__baseUri.resolve("input.txt"));
            assertEquals(HttpServletResponse.SC_OK, response.getStatus());
            assertEquals(_content, response.getContentAsString());

            stopClient();

            String newpwd = String.valueOf(NanoTime.now());

            changePassword("dstest", newpwd);

            setupClient();
            _authStore.addAuthentication(new BasicAuthentication(__baseUri, REALM_NAME, "dstest", newpwd));
            _client.start();

            response = _client.GET(__baseUri.resolve("input.txt"));
            assertEquals(HttpServletResponse.SC_OK, response.getStatus());
            assertEquals(_content, response.getContentAsString());
        }
        finally
        {
            changePassword("dstest", "dstest");
        }
    }

    protected void changePassword(String user, String newpwd) throws Exception
    {
        Loader.loadClass(DatabaseLoginServiceTestServer.MARIA_DB_DRIVER_CLASS);
        try (Connection connection = DriverManager.getConnection(DatabaseLoginServiceTestServer.MARIA_DB_FULL_URL);
             Statement stmt = connection.createStatement())
        {
            connection.setAutoCommit(true);
            stmt.executeUpdate("update users set pwd='" + newpwd + "' where username='" + user + "'");
        }
    }
}
