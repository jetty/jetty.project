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

package org.eclipse.jetty.ee10.loginservice;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.PrintWriter;
import java.net.URI;
import java.nio.charset.StandardCharsets;

import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.AuthenticationStore;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.client.util.BasicAuthentication;
import org.eclipse.jetty.client.util.BytesRequestContent;
import org.eclipse.jetty.ee10.servlet.security.JDBCLoginService;
import org.eclipse.jetty.ee10.servlet.security.LoginService;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.toolchain.test.FS;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.util.IO;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Disabled //TODO needs DefaultServlet
@Testcontainers(disabledWithoutDocker = true)
public class JdbcLoginServiceTest
{
    private static String _content =
        "Lorem ipsum dolor sit amet, consectetur adipiscing elit.";

    private static File __docRoot;
    private static String __realm = "JdbcRealm";
    private static URI __baseUri;
    private static DatabaseLoginServiceTestServer __testServer;
    private HttpClient _client;
    private AuthenticationStore _authStore;

    @BeforeAll
    public static void setUp() throws Exception
    {
        File dir = MavenTestingUtils.getTargetTestingDir("jdbcloginservice-test");
        FS.ensureDirExists(dir);

        //create the realm properties file based on dynamic + static info
        File skeletonFile = MavenTestingUtils.getTestResourceFile("jdbcrealm.properties");
        File realmFile = new File(dir, "realm.properties");
        try (PrintWriter writer = new PrintWriter(new FileOutputStream(realmFile)))
        {
            writer.println("jdbcdriver = " + DatabaseLoginServiceTestServer.MARIA_DB_DRIVER_CLASS);
            writer.println("url = " + DatabaseLoginServiceTestServer.MARIA_DB_URL);
            writer.println("username = " + DatabaseLoginServiceTestServer.MARIA_DB_USER);
            writer.println("password = " + DatabaseLoginServiceTestServer.MARIA_DB_PASSWORD);
            IO.copy(new FileReader(skeletonFile), writer);
        }

        //make some static content
        __docRoot = new File(dir, "docroot");
        FS.ensureDirExists(__docRoot);
        File content = new File(__docRoot, "input.txt");
        try (FileOutputStream out = new FileOutputStream(content))
        {
            out.write(_content.getBytes(StandardCharsets.UTF_8));
        }

        LoginService loginService = new JDBCLoginService(__realm, realmFile.getAbsolutePath());
        
        __testServer = new DatabaseLoginServiceTestServer();
        __testServer.setResourceBase(__docRoot.toPath());
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
    public void testPut() throws Exception
    {
        _authStore.addAuthentication(new BasicAuthentication(__baseUri, __realm, "jetty", "jetty"));
        _client.start();

        Request request = _client.newRequest(__baseUri.resolve("output.txt"));
        request.method(HttpMethod.PUT);
        request.body(new BytesRequestContent(_content.getBytes()));
        ContentResponse response = request.send();
        int responseStatus = response.getStatus();
        boolean statusOk = (responseStatus == 200 || responseStatus == 201);
        assertTrue(statusOk);
        String content = IO.toString(new FileInputStream(new File(__docRoot, "output.txt")));
        assertEquals(_content, content);
    }

    @Test
    public void testGet() throws Exception
    {
        _authStore.addAuthentication(new BasicAuthentication(__baseUri, __realm, "jetty", "jetty"));
        _client.start();
        
        ContentResponse response = _client.GET(__baseUri.resolve("input.txt"));
        assertEquals(HttpServletResponse.SC_OK, response.getStatus());
        assertEquals(_content, response.getContentAsString());
    }

    @Test
    public void testGetNonExistantUser() throws Exception
    {
        _authStore.addAuthentication(new BasicAuthentication(__baseUri, __realm, "foo", "bar"));
        _client.start();

        ContentResponse response = _client.GET(__baseUri.resolve("input.txt"));
        assertEquals(HttpServletResponse.SC_UNAUTHORIZED, response.getStatus());
    }

    @Test
    public void testHead() throws Exception
    {
        _authStore.addAuthentication(new BasicAuthentication(__baseUri, __realm, "jetty", "jetty"));
        _client.start();

        Request request = _client.newRequest(__baseUri.resolve("input.txt"));
        request.method(HttpMethod.HEAD);
        ContentResponse response = request.send();
        int responseStatus = response.getStatus();
        assertEquals(HttpStatus.OK_200, responseStatus);
    }

    @Test
    public void testPost() throws Exception
    {
        _authStore.addAuthentication(new BasicAuthentication(__baseUri, __realm, "jetty", "jetty"));
        _client.start();

        Request request = _client.newRequest(__baseUri.resolve("test"));
        request.method(HttpMethod.POST);
        request.body(new BytesRequestContent(_content.getBytes()));
        ContentResponse response = request.send();
        assertEquals(HttpStatus.OK_200, response.getStatus());
        assertEquals(_content, __testServer.getTestFilter().getRequestContent());
    }
}
