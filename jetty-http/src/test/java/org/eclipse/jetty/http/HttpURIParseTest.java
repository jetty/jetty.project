//
//  ========================================================================
//  Copyright (c) 1995-2015 Mort Bay Consulting Pty. Ltd.
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

import static org.hamcrest.Matchers.*;
import static org.junit.Assert.*;
import static org.junit.Assume.*;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.List;

import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameter;
import org.junit.runners.Parameterized.Parameters;


@RunWith(Parameterized.class)
public class HttpURIParseTest
{
    public static int INPUT=0,SCHEME=1,HOST=2,PORT=3,PATH=4,PARAM=5,QUERY=6,FRAGMENT=7;

    @Parameters(name="{0}")
    public static List<String[]> data()
    {
        String[][] tests = {
                
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
        
        // Protocol Less (aka scheme-less) URIs
        
        // FIXME (these have host and port)
        {"//host/path/info",null,null,null,"//host/path/info",null,null,null},
        {"//user@host/path/info",null,null,null,"//user@host/path/info",null,null,null},
        {"//user@host:8080/path/info",null,null,null,"//user@host:8080/path/info",null,null,null},
        {"//host:8080/path/info",null,null,null,"//host:8080/path/info",null,null,null},
        
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
        
        // FIXME ("user@" authentication information is lost during parse and toString)
        {"http://user@host:8080/path/info;param?query#fragment","http","host","8080","/path/info;param","param","query","fragment"},
        {"xxxxx://user@host:8080/path/info;param?query#fragment","xxxxx","host","8080","/path/info;param","param","query","fragment"},
        
        // No host, parameter with no content
        
        // FIXME (no host, should result in null for host, not empty string)
        {"http:///;?#","http","",null,"/;","","",""},
        
        // Path with query that has no value
        
        {"/path/info?a=?query",null,null,null,"/path/info",null,"a=?query",null},
        
        // Path with query alt syntax
        
        {"/path/info?a=;query",null,null,null,"/path/info",null,"a=;query",null},
        
        // Scheme-less, with host and port (overlapping with path)
        
        // FIXME (this has host and port)
        {"//host:8080//",null,null,null,"//host:8080//",null,null,null},
        
        // File reference
        
        // FIXME (no host, should result in null for host, not empty string)
        {"file:///path/info","file","",null,"/path/info",null,null,null},
        {"file:/path/info","file",null,null,"/path/info",null,null,null},
        
        // Without Authority (this is a bad URI according to spec) 
        
        {"//",null,null,null,"//",null,null,null},
        
        // Simple Localhost references
        
        {"http://localhost/","http","localhost",null,"/",null,null,null},
        {"http://localhost:8080/", "http", "localhost","8080","/", null, null,null},
        {"http://localhost/?x=y", "http", "localhost",null,"/", null,"x=y",null},
        
        // Simple path with parameter 
        
        {"/;param",null, null,null,"/;param", "param",null,null},
        
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
        
        // FIXME ("user@" authentication information is lost during parse and toString)
        {"http://user@[2001:db8::1]:8080/","http","[2001:db8::1]","8080","/",null,null,null},
        
        // Simple IPv6 host no port (default path)
        
        {"http://[2001:db8::1]/","http","[2001:db8::1]",null,"/",null,null,null},
        
        // Scheme-less IPv6, host with port (default path)
        
        // FIXME (this has host and port)
        {"//[2001:db8::1]:8080/",null,null,null,"//[2001:db8::1]:8080/",null,null,null},
        
        // Interpreted as relative path of "*" (no host/port/scheme/query/fragment)
        
        {"*",null,null,null,"*",null, null,null},

        // Path detection Tests (seen from JSP/JSTL and <c:url> use
        {"http://host:8080/path/info?q1=v1&q2=v2","http","host","8080","/path/info",null,"q1=v1&q2=v2",null},
        {"/path/info?q1=v1&q2=v2",null,null,null,"/path/info",null,"q1=v1&q2=v2",null},
        {"/info?q1=v1&q2=v2",null,null,null,"/info",null,"q1=v1&q2=v2",null},
        // FIXME (Bad Path/Query results) {"info?q1=v1&q2=v2",null,null,null,"info",null,"q1=v1&q2=v2",null},
        // FIXME (StringIndexOutOfBoundsException) {"info;q1=v1?q2=v2",null,null,null,"info;q1=v1",null,"q2=v2",null},
        
        // Path-less, query only (seen from JSP/JSTL and <c:url> use
        // FIXME (path should be null in parse(URI) version)
        {"?q1=v1&q2=v2",null,null,null,null,null,"q1=v1&q2=v2",null}
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
    @Ignore("There are many examples of inconsistent results from .testParseString()")
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
    @Ignore("There are many examples of inconsistent results from .testParseString()")
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
        assertThat("[" + input + "] .path",httpUri.getPath(),is(javaUri.getPath()));
        // Not Relevant for java.net.URI -- assertThat("["+input+"] .param", httpUri.getParam(), is(param));
        assertThat("[" + input + "] .query",httpUri.getQuery(),is(javaUri.getRawQuery()));
        assertThat("[" + input + "] .fragment",httpUri.getFragment(),is(javaUri.getFragment()));
        assertThat("[" + input + "] .toString",httpUri.toString(),is(javaUri.toASCIIString()));
    }
}