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

import java.net.URI;
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
        {"/path/to/context",null,null,"-1","/path/to/context",null,null,null},
        {"http://example.com/path/to/context;param?query=%22value%22#fragment","http","example.com","-1","/path/to/context;param","param","query=%22value%22","fragment"},
        {"http://[::1]/path/to/context;param?query=%22value%22#fragment","http","[::1]","-1","/path/to/context;param","param","query=%22value%22","fragment"},
        {"http://example.com:8080/path/to/context;param?query=%22value%22#fragment","http","example.com","8080","/path/to/context;param","param","query=%22value%22","fragment"},
        {"http://[::1]:8080/path/to/context;param?query=%22value%22#fragment","http","[::1]","8080","/path/to/context;param","param","query=%22value%22","fragment"},
        
        {"/path/info",null,null,null,"/path/info",null,null,null},
        {"/path/info#fragment",null,null,null,"/path/info",null,null,"fragment"},
        {"/path/info?query",null,null,null,"/path/info",null,"query",null},
        {"/path/info?query#fragment",null,null,null,"/path/info",null,"query","fragment"},
        {"/path/info;param",null,null,null,"/path/info;param","param",null,null},
        {"/path/info;param#fragment",null,null,null,"/path/info;param","param",null,"fragment"},
        {"/path/info;param?query",null,null,null,"/path/info;param","param","query",null},
        {"/path/info;param?query#fragment",null,null,null,"/path/info;param","param","query","fragment"},
        {"//host/path/info",null,null,null,"//host/path/info",null,null,null},
        {"//user@host/path/info",null,null,null,"//user@host/path/info",null,null,null},
        {"//user@host:8080/path/info",null,null,null,"//user@host:8080/path/info",null,null,null},
        {"//host:8080/path/info",null,null,null,"//host:8080/path/info",null,null,null},
        {"http:/path/info","http",null,null,"/path/info",null,null,null},
        {"http:/path/info#fragment","http",null,null,"/path/info",null,null,"fragment"},
        {"http:/path/info?query","http",null,null,"/path/info",null,"query",null},
        {"http:/path/info?query#fragment","http",null,null,"/path/info",null,"query","fragment"},
        {"http:/path/info;param","http",null,null,"/path/info;param","param",null,null},
        {"http:/path/info;param#fragment","http",null,null,"/path/info;param","param",null,"fragment"},
        {"http:/path/info;param?query","http",null,null,"/path/info;param","param","query",null},
        {"http:/path/info;param?query#fragment","http",null,null,"/path/info;param","param","query","fragment"},
        {"http://user@host:8080/path/info;param?query#fragment","http","host","8080","/path/info;param","param","query","fragment"},
        {"xxxxx://user@host:8080/path/info;param?query#fragment","xxxxx","host","8080","/path/info;param","param","query","fragment"},
        {"http:///;?#","http","",null,"/;","","",""},
        {"/path/info?a=?query",null,null,null,"/path/info",null,"a=?query",null},
        {"/path/info?a=;query",null,null,null,"/path/info",null,"a=;query",null},
        {"//host:8080//",null,null,null,"//host:8080//",null,null,null},
        {"file:///path/info","file","",null,"/path/info",null,null,null},
        {"//",null,null,null,"//",null,null,null},
        {"http://localhost/","http","localhost",null,"/",null,null,null},
        {"http://localhost:8080/", "http", "localhost","8080","/", null, null,null},
        {"http://localhost/?x=y", "http", "localhost",null,"/", null,"x=y",null},
        {"/;param",null, null,null,"/;param", "param",null,null},
        {"/?x=y",null, null,null,"/", null,"x=y",null},
        {"/?abc=test",null, null,null,"/", null,"abc=test",null},
        {"/#fragment",null, null,null,"/", null,null,"fragment"},
        {"http://192.0.0.1:8080/","http","192.0.0.1","8080","/",null,null,null},
        {"http://[2001:db8::1]:8080/","http","[2001:db8::1]","8080","/",null,null,null},
        {"http://user@[2001:db8::1]:8080/","http","[2001:db8::1]","8080","/",null,null,null},
        {"http://[2001:db8::1]/","http","[2001:db8::1]",null,"/",null,null,null},
        {"//[2001:db8::1]:8080/",null,null,null,"//[2001:db8::1]:8080/",null,null,null},
        {"http://user@[2001:db8::1]:8080/","http","[2001:db8::1]","8080","/",null,null,null},
        {"*",null,null,null,"*",null, null,null},
        {"http://host:8080/path/info?q1=v1&q2=v2","http","host","8080","/path/info",null,"q1=v1&q2=v2",null},
        {"/path/info?q1=v1&q2=v2",null,null,null,"/path/info",null,"q1=v1&q2=v2",null},
        {"/info?q1=v1&q2=v2",null,null,null,"/info",null,"q1=v1&q2=v2",null},
        // FIXME (Bad Path/Query results) {"info?q1=v1&q2=v2",null,null,null,"info",null,"q1=v1&q2=v2",null},
        // FIXME (StringIndexOutOfBoundsException) {"info;q1=v1?q2=v2",null,null,null,"info;q1=v1",null,"q2=v2",null},
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
        
        assertThat("["+input+"] .scheme", httpUri.getScheme(), is(scheme));
        assertThat("["+input+"] .host", httpUri.getHost(), is(host));
        assertThat("["+input+"] .port", httpUri.getPort(), is(port==null?-1:Integer.parseInt(port)));
        assertThat("["+input+"] .path", httpUri.getPath(), is(path));
        assertThat("["+input+"] .param", httpUri.getParam(), is(param));
        assertThat("["+input+"] .query", httpUri.getQuery(), is(query));
        assertThat("["+input+"] .fragment", httpUri.getFragment(), is(fragment));
        assertThat("["+input+"] .toString", httpUri.toString(), is(input));
    }
    
    @Test
    @Ignore("There are many examples of inconsistent results from .testParseString()")
    public void testParseURI() throws Exception
    {
        URI javaUri = new URI(input);
        HttpURI httpUri = new HttpURI(javaUri);
        
        assertThat("["+input+"] .scheme", httpUri.getScheme(), is(scheme));
        assertThat("["+input+"] .host", httpUri.getHost(), is(host));
        assertThat("["+input+"] .port", httpUri.getPort(), is(port==null?-1:Integer.parseInt(port)));
        assertThat("["+input+"] .path", httpUri.getPath(), is(path));
        assertThat("["+input+"] .param", httpUri.getParam(), is(param));
        assertThat("["+input+"] .query", httpUri.getQuery(), is(query));
        assertThat("["+input+"] .fragment", httpUri.getFragment(), is(fragment));
        assertThat("["+input+"] .toString", httpUri.toString(), is(input));
    }
    
    @Test
    @Ignore("There are many examples of inconsistent results from .testParseString()")
    public void testCompareToJavaNetURI() throws Exception
    {
        URI javaUri = new URI(input);
        HttpURI httpUri = new HttpURI(javaUri);
        
        assertThat("["+input+"] .scheme", httpUri.getScheme(), is(javaUri.getScheme()));
        assertThat("["+input+"] .host", httpUri.getHost(), is(javaUri.getHost()));
        assertThat("["+input+"] .port", httpUri.getPort(), is(javaUri.getPort()));
        assertThat("["+input+"] .path", httpUri.getPath(), is(javaUri.getPath()));
        // Not Relevant for java.net.URI assertThat("["+input+"] .param", httpUri.getParam(), is(param));
        assertThat("["+input+"] .query", httpUri.getQuery(), is(javaUri.getRawQuery()));
        assertThat("["+input+"] .fragment", httpUri.getFragment(), is(javaUri.getFragment()));
        assertThat("["+input+"] .toString", httpUri.toString(), is(javaUri.toASCIIString()));
    }
}