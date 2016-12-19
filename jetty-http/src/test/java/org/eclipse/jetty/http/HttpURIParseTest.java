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


package org.eclipse.jetty.http;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameter;
import org.junit.runners.Parameterized.Parameters;

import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.Assert.assertThat;
import static org.junit.Assume.assumeNoException;
import static org.junit.Assume.assumeNotNull;


@RunWith(Parameterized.class)
public class HttpURIParseTest
{
    @Parameters(name="{0}")
    public static List<String[]> data()
    {
        String[][] tests = {

        // Nothing but path 
        {"path",null,null,"-1","path",null,null,null},
        {"path/path",null,null,"-1","path/path",null,null,null},
        {"%65ncoded/path",null,null,"-1","%65ncoded/path",null,null,null},
                
        // Basic path reference     
        {"/path/to/context",null,null,"-1","/path/to/context",null,null,null},
        
        // Basic with encoded query 
        {"http://example.com/path/to/context;param?query=%22value%22#fragment","http","example.com","-1","/path/to/context;param","param","query=%22value%22","fragment"},
        {"http://[::1]/path/to/context;param?query=%22value%22#fragment","http","[::1]","-1","/path/to/context;param","param","query=%22value%22","fragment"},
        
        // Basic with parameters and query
        {"http://example.com:8080/path/to/context;param?query=%22value%22#fragment","http","example.com","8080","/path/to/context;param","param","query=%22value%22","fragment"},
        {"http://[::1]:8080/path/to/context;param?query=%22value%22#fragment","http","[::1]","8080","/path/to/context;param","param","query=%22value%22","fragment"},
        
        // Path References
        {"/path/info",null,null,null,"/path/info",null,null,null},
        {"/path/info#fragment",null,null,null,"/path/info",null,null,"fragment"},
        {"/path/info?query",null,null,null,"/path/info",null,"query",null},
        {"/path/info?query#fragment",null,null,null,"/path/info",null,"query","fragment"},
        {"/path/info;param",null,null,null,"/path/info;param","param",null,null},
        {"/path/info;param#fragment",null,null,null,"/path/info;param","param",null,"fragment"},
        {"/path/info;param?query",null,null,null,"/path/info;param","param","query",null},
        {"/path/info;param?query#fragment",null,null,null,"/path/info;param","param","query","fragment"},
        {"/path/info;a=b/foo;c=d",null,null,null,"/path/info;a=b/foo;c=d","c=d",null,null}, // TODO #405

        // Protocol Less (aka scheme-less) URIs
        {"//host/path/info",null,"host",null,"/path/info",null,null,null},
        {"//user@host/path/info",null,"host",null,"/path/info",null,null,null},
        {"//user@host:8080/path/info",null,"host","8080","/path/info",null,null,null},
        {"//host:8080/path/info",null,"host","8080","/path/info",null,null,null},
        
        // Host Less 
        {"http:/path/info","http",null,null,"/path/info",null,null,null},
        {"http:/path/info#fragment","http",null,null,"/path/info",null,null,"fragment"},
        {"http:/path/info?query","http",null,null,"/path/info",null,"query",null},
        {"http:/path/info?query#fragment","http",null,null,"/path/info",null,"query","fragment"},
        {"http:/path/info;param","http",null,null,"/path/info;param","param",null,null},
        {"http:/path/info;param#fragment","http",null,null,"/path/info;param","param",null,"fragment"},
        {"http:/path/info;param?query","http",null,null,"/path/info;param","param","query",null},
        {"http:/path/info;param?query#fragment","http",null,null,"/path/info;param","param","query","fragment"},
        
        // Everything and the kitchen sink
        {"http://user@host:8080/path/info;param?query#fragment","http","host","8080","/path/info;param","param","query","fragment"},
        {"xxxxx://user@host:8080/path/info;param?query#fragment","xxxxx","host","8080","/path/info;param","param","query","fragment"},
        
        // No host, parameter with no content
        {"http:///;?#","http",null,null,"/;","","",""},
        
        // Path with query that has no value
        {"/path/info?a=?query",null,null,null,"/path/info",null,"a=?query",null},
        
        // Path with query alt syntax
        {"/path/info?a=;query",null,null,null,"/path/info",null,"a=;query",null},

        // URI with host character
        {"/@path/info",null,null,null,"/@path/info",null,null,null},
        {"/user@path/info",null,null,null,"/user@path/info",null,null,null},
        {"//user@host/info",null,"host",null,"/info",null,null,null},
        {"//@host/info",null,"host",null,"/info",null,null,null},
        {"@host/info",null,null,null,"@host/info",null,null,null},
        
        // Scheme-less, with host and port (overlapping with path)
        {"//host:8080//",null,"host","8080","//",null,null,null},
        
        // File reference
        {"file:///path/info","file",null,null,"/path/info",null,null,null},
        {"file:/path/info","file",null,null,"/path/info",null,null,null},
        
        // Bad URI (no scheme, no host, no path) 
        {"//",null,null,null,null,null,null,null},
        
        // Simple localhost references
        {"http://localhost/","http","localhost",null,"/",null,null,null},
        {"http://localhost:8080/", "http", "localhost","8080","/", null, null,null},
        {"http://localhost/?x=y", "http", "localhost",null,"/", null,"x=y",null},
        
        // Simple path with parameter 
        {"/;param",null, null,null,"/;param", "param",null,null},
        {";param",null, null,null,";param", "param",null,null},
        
        // Simple path with query
        {"/?x=y",null, null,null,"/", null,"x=y",null},
        {"/?abc=test",null, null,null,"/", null,"abc=test",null},
        
        // Simple path with fragment
        {"/#fragment",null, null,null,"/", null,null,"fragment"},
        
        // Simple IPv4 host with port (default path)
        {"http://192.0.0.1:8080/","http","192.0.0.1","8080","/",null,null,null},
        
        // Simple IPv6 host with port (default path)
        
        {"http://[2001:db8::1]:8080/","http","[2001:db8::1]","8080","/",null,null,null},
        // IPv6 authenticated host with port (default path)
        
        {"http://user@[2001:db8::1]:8080/","http","[2001:db8::1]","8080","/",null,null,null},
        
        // Simple IPv6 host no port (default path)
        {"http://[2001:db8::1]/","http","[2001:db8::1]",null,"/",null,null,null},
        
        // Scheme-less IPv6, host with port (default path)
        {"//[2001:db8::1]:8080/",null,"[2001:db8::1]","8080","/",null,null,null},
        
        // Interpreted as relative path of "*" (no host/port/scheme/query/fragment)
        {"*",null,null,null,"*",null, null,null},

        // Path detection Tests (seen from JSP/JSTL and <c:url> use)
        {"http://host:8080/path/info?q1=v1&q2=v2","http","host","8080","/path/info",null,"q1=v1&q2=v2",null},
        {"/path/info?q1=v1&q2=v2",null,null,null,"/path/info",null,"q1=v1&q2=v2",null},
        {"/info?q1=v1&q2=v2",null,null,null,"/info",null,"q1=v1&q2=v2",null},
        {"info?q1=v1&q2=v2",null,null,null,"info",null,"q1=v1&q2=v2",null},
        {"info;q1=v1?q2=v2",null,null,null,"info;q1=v1","q1=v1","q2=v2",null},
        
        // Path-less, query only (seen from JSP/JSTL and <c:url> use)
        {"?q1=v1&q2=v2",null,null,null,"",null,"q1=v1&q2=v2",null}
        };
        
        return Arrays.asList(tests);
    }
    
    @Parameter(0)
    public String input;

    @Parameter(1)
    public String scheme;
    
    @Parameter(2)
    public String host;
    
    @Parameter(3)
    public String port;

    @Parameter(4)
    public String path;
    
    @Parameter(5)
    public String param;
    
    @Parameter(6)
    public String query;
    
    @Parameter(7)
    public String fragment;
    
    @Test
    public void testParseString() throws Exception
    {
        HttpURI httpUri = new HttpURI(input);
        
        try
        {
            new URI(input);
            // URI is valid (per java.net.URI parsing)
            
            // Test case sanity check
            assertThat("[" + input + "] expected path (test case) cannot be null",path,notNullValue());

            // Assert expectations
            assertThat("[" + input + "] .scheme",httpUri.getScheme(),is(scheme));
            assertThat("[" + input + "] .host",httpUri.getHost(),is(host));
            assertThat("[" + input + "] .port",httpUri.getPort(),is(port == null ? -1 : Integer.parseInt(port)));
            assertThat("[" + input + "] .path",httpUri.getPath(),is(path));
            assertThat("[" + input + "] .param",httpUri.getParam(),is(param));
            assertThat("[" + input + "] .query",httpUri.getQuery(),is(query));
            assertThat("[" + input + "] .fragment",httpUri.getFragment(),is(fragment));
            assertThat("[" + input + "] .toString",httpUri.toString(),is(input));
        }
        catch (URISyntaxException e)
        {
            // Assert HttpURI values for invalid URI (such as "//")
            assertThat("[" + input + "] .scheme",httpUri.getScheme(),is(nullValue()));
            assertThat("[" + input + "] .host",httpUri.getHost(),is(nullValue()));
            assertThat("[" + input + "] .port",httpUri.getPort(),is(-1));
            assertThat("[" + input + "] .path",httpUri.getPath(),is(nullValue()));
            assertThat("[" + input + "] .param",httpUri.getParam(),is(nullValue()));
            assertThat("[" + input + "] .query",httpUri.getQuery(),is(nullValue()));
            assertThat("[" + input + "] .fragment",httpUri.getFragment(),is(nullValue()));
        }
    }
    
    @Test
    public void testParseURI() throws Exception
    {
        URI javaUri = null;
        try
        {
            javaUri = new URI(input);
            assumeNotNull(javaUri);
        }
        catch (URISyntaxException e)
        {
            // Ignore, as URI is invalid anyway
            assumeNoException(e);
        }
        
        HttpURI httpUri = new HttpURI(javaUri);

        assertThat("[" + input + "] .scheme",httpUri.getScheme(),is(scheme));
        assertThat("[" + input + "] .host",httpUri.getHost(),is(host));
        assertThat("[" + input + "] .port",httpUri.getPort(),is(port == null ? -1 : Integer.parseInt(port)));
        assertThat("[" + input + "] .path",httpUri.getPath(),is(path));
        assertThat("[" + input + "] .param",httpUri.getParam(),is(param));
        assertThat("[" + input + "] .query",httpUri.getQuery(),is(query));
        assertThat("[" + input + "] .fragment",httpUri.getFragment(),is(fragment));
        
        assertThat("[" + input + "] .toString",httpUri.toString(),is(input));
    }
    
    @Test
    public void testCompareToJavaNetURI() throws Exception
    {
        URI javaUri = null;
        try
        {
            javaUri = new URI(input);
            assumeNotNull(javaUri);
        }
        catch (URISyntaxException e)
        {
            // Ignore, as URI is invalid anyway
            assumeNoException(e);
        }
        
        HttpURI httpUri = new HttpURI(javaUri);
        
        assertThat("[" + input + "] .scheme",httpUri.getScheme(),is(javaUri.getScheme()));
        assertThat("[" + input + "] .host",httpUri.getHost(),is(javaUri.getHost()));
        assertThat("[" + input + "] .port",httpUri.getPort(),is(javaUri.getPort()));
        assertThat("[" + input + "] .path",httpUri.getPath(),is(javaUri.getRawPath()));
        // Not Relevant for java.net.URI -- assertThat("["+input+"] .param", httpUri.getParam(), is(param));
        assertThat("[" + input + "] .query",httpUri.getQuery(),is(javaUri.getRawQuery()));
        assertThat("[" + input + "] .fragment",httpUri.getFragment(),is(javaUri.getFragment()));
        assertThat("[" + input + "] .toString",httpUri.toString(),is(javaUri.toASCIIString()));
    }
}