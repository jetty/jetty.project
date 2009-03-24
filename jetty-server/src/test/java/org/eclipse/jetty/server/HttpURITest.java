// ========================================================================
// Copyright (c) 2006-2009 Mort Bay Consulting Pty. Ltd.
// ------------------------------------------------------------------------
// All rights reserved. This program and the accompanying materials
// are made available under the terms of the Eclipse Public License v1.0
// and Apache License v2.0 which accompanies this distribution.
// The Eclipse Public License is available at 
// http://www.eclipse.org/legal/epl-v10.html
// The Apache License v2.0 is available at
// http://www.opensource.org/licenses/apache2.0.php
// You may elect to redistribute this code under either of these licenses. 
// ========================================================================

package org.eclipse.jetty.server;

import junit.framework.TestCase;

import org.eclipse.jetty.http.HttpURI;

public class HttpURITest extends TestCase
{
    String[][] partial_tests=
    { 
       /* 0*/ {"/path/info",null,null,null,null,"/path/info",null,null,null}, 
       /* 1*/ {"/path/info#fragment",null,null,null,null,"/path/info",null,null,"fragment"}, 
       /* 2*/ {"/path/info?query",null,null,null,null,"/path/info",null,"query",null}, 
       /* 3*/ {"/path/info?query#fragment",null,null,null,null,"/path/info",null,"query","fragment"}, 
       /* 4*/ {"/path/info;param",null,null,null,null,"/path/info","param",null,null},    
       /* 5*/ {"/path/info;param#fragment",null,null,null,null,"/path/info","param",null,"fragment"}, 
       /* 6*/ {"/path/info;param?query",null,null,null,null,"/path/info","param","query",null}, 
       /* 7*/ {"/path/info;param?query#fragment",null,null,null,null,"/path/info","param","query","fragment"}, 
       /* 8*/ {"//host/path/info",null,"//host","host",null,"/path/info",null,null,null}, 
       /* 9*/ {"//user@host/path/info",null,"//user@host","host",null,"/path/info",null,null,null}, 
       /*10*/ {"//user@host:8080/path/info",null,"//user@host:8080","host","8080","/path/info",null,null,null}, 
       /*11*/ {"//host:8080/path/info",null,"//host:8080","host","8080","/path/info",null,null,null}, 
       /*12*/ {"http:/path/info","http",null,null,null,"/path/info",null,null,null},    
       /*13*/ {"http:/path/info#fragment","http",null,null,null,"/path/info",null,null,"fragment"}, 
       /*14*/ {"http:/path/info?query","http",null,null,null,"/path/info",null,"query",null}, 
       /*15*/ {"http:/path/info?query#fragment","http",null,null,null,"/path/info",null,"query","fragment"}, 
       /*16*/ {"http:/path/info;param","http",null,null,null,"/path/info","param",null,null},    
       /*17*/ {"http:/path/info;param#fragment","http",null,null,null,"/path/info","param",null,"fragment"}, 
       /*18*/ {"http:/path/info;param?query","http",null,null,null,"/path/info","param","query",null}, 
       /*19*/ {"http:/path/info;param?query#fragment","http",null,null,null,"/path/info","param","query","fragment"},                
       /*20*/ {"http://user@host:8080/path/info;param?query#fragment","http","//user@host:8080","host","8080","/path/info","param","query","fragment"}, 
       /*21*/ {"xxxxx://user@host:8080/path/info;param?query#fragment","xxxxx","//user@host:8080","host","8080","/path/info","param","query","fragment"}, 
       /*22*/ {"http:///;?#","http","//",null,null,"/","","",""}, 
       /*23*/ {"/path/info?a=?query",null,null,null,null,"/path/info",null,"a=?query",null}, 
       /*24*/ {"/path/info?a=;query",null,null,null,null,"/path/info",null,"a=;query",null}, 
       /*25*/ {"//host:8080//",null,"//host:8080","host","8080","//",null,null,null}, 
       /*26*/ {"file:///path/info","file","//",null,null,"/path/info",null,null,null},    
       /*27*/ {"//",null,"//",null,null,null,null,null,null},  
       /*28*/ {"/;param",null, null, null,null,"/", "param",null,null},
       /*29*/ {"/?x=y",null, null, null,null,"/", null,"x=y",null},
       /*30*/ {"/?abc=test",null, null, null,null,"/", null,"abc=test",null},
       /*31*/ {"/#fragment",null, null, null,null,"/", null,null,"fragment"},  
    };

    public void testPartialURIs()
        throws Exception
    {
        HttpURI uri = new HttpURI(true);
        
        for (int t=0;t<partial_tests.length;t++)
        {
            uri.parse(partial_tests[t][0].getBytes(),0,partial_tests[t][0].length());
            assertEquals(t+" "+partial_tests[t][0],partial_tests[t][1],uri.getScheme());
            assertEquals(t+" "+partial_tests[t][0],partial_tests[t][2],uri.getAuthority());
            assertEquals(t+" "+partial_tests[t][0],partial_tests[t][3],uri.getHost());
            assertEquals(t+" "+partial_tests[t][0],partial_tests[t][4]==null?-1:Integer.parseInt(partial_tests[t][4]),uri.getPort());
            assertEquals(t+" "+partial_tests[t][0],partial_tests[t][5],uri.getPath());
            assertEquals(t+" "+partial_tests[t][0],partial_tests[t][6],uri.getParam());
            assertEquals(t+" "+partial_tests[t][0],partial_tests[t][7],uri.getQuery());
            assertEquals(t+" "+partial_tests[t][0],partial_tests[t][8],uri.getFragment());
            assertEquals(partial_tests[t][0], uri.toString());
        }
        
    }

    String[][] path_tests=
    { 
       /* 0*/ {"/path/info",null,null,null,null,"/path/info",null,null,null}, 
       /* 1*/ {"/path/info#fragment",null,null,null,null,"/path/info",null,null,"fragment"}, 
       /* 2*/ {"/path/info?query",null,null,null,null,"/path/info",null,"query",null}, 
       /* 3*/ {"/path/info?query#fragment",null,null,null,null,"/path/info",null,"query","fragment"}, 
       /* 4*/ {"/path/info;param",null,null,null,null,"/path/info","param",null,null},    
       /* 5*/ {"/path/info;param#fragment",null,null,null,null,"/path/info","param",null,"fragment"}, 
       /* 6*/ {"/path/info;param?query",null,null,null,null,"/path/info","param","query",null}, 
       /* 7*/ {"/path/info;param?query#fragment",null,null,null,null,"/path/info","param","query","fragment"}, 
       /* 8*/ {"//host/path/info",null,null,null,null,"//host/path/info",null,null,null}, 
       /* 9*/ {"//user@host/path/info",null,null,null,null,"//user@host/path/info",null,null,null}, 
       /*10*/ {"//user@host:8080/path/info",null,null,null,null,"//user@host:8080/path/info",null,null,null}, 
       /*11*/ {"//host:8080/path/info",null,null,null,null,"//host:8080/path/info",null,null,null}, 
       /*12*/ {"http:/path/info","http",null,null,null,"/path/info",null,null,null},    
       /*13*/ {"http:/path/info#fragment","http",null,null,null,"/path/info",null,null,"fragment"}, 
       /*14*/ {"http:/path/info?query","http",null,null,null,"/path/info",null,"query",null}, 
       /*15*/ {"http:/path/info?query#fragment","http",null,null,null,"/path/info",null,"query","fragment"}, 
       /*16*/ {"http:/path/info;param","http",null,null,null,"/path/info","param",null,null},    
       /*17*/ {"http:/path/info;param#fragment","http",null,null,null,"/path/info","param",null,"fragment"}, 
       /*18*/ {"http:/path/info;param?query","http",null,null,null,"/path/info","param","query",null}, 
       /*19*/ {"http:/path/info;param?query#fragment","http",null,null,null,"/path/info","param","query","fragment"},                
       /*20*/ {"http://user@host:8080/path/info;param?query#fragment","http","//user@host:8080","host","8080","/path/info","param","query","fragment"}, 
       /*21*/ {"xxxxx://user@host:8080/path/info;param?query#fragment","xxxxx","//user@host:8080","host","8080","/path/info","param","query","fragment"}, 
       /*22*/ {"http:///;?#","http","//",null,null,"/","","",""}, 
       /*23*/ {"/path/info?a=?query",null,null,null,null,"/path/info",null,"a=?query",null}, 
       /*24*/ {"/path/info?a=;query",null,null,null,null,"/path/info",null,"a=;query",null}, 
       /*25*/ {"//host:8080//",null,null,null,null,"//host:8080//",null,null,null}, 
       /*26*/ {"file:///path/info","file","//",null,null,"/path/info",null,null,null},    
       /*27*/ {"//",null,null,null,null,"//",null,null,null}, 
       /*28*/ {"http://localhost/","http","//localhost","localhost",null,"/",null,null,null},
       /*29*/ {"http://localhost:8080/", "http", "//localhost:8080", "localhost","8080","/", null, null,null},
       /*30*/ {"http://localhost/?x=y", "http", "//localhost", "localhost",null,"/", null,"x=y",null},
       /*31*/ {"/;param",null, null, null,null,"/", "param",null,null},
       /*32*/ {"/?x=y",null, null, null,null,"/", null,"x=y",null},
       /*33*/ {"/?abc=test",null, null, null,null,"/", null,"abc=test",null},
       /*34*/ {"/#fragment",null, null, null,null,"/", null,null,"fragment"},
       /*35*/ {"http://192.0.0.1:8080/","http","//192.0.0.1:8080","192.0.0.1","8080","/",null,null,null},
       /*36*/ {"http://[2001:db8::1]:8080/","http","//[2001:db8::1]:8080","[2001:db8::1]","8080","/",null,null,null},
       /*37*/ {"http://user@[2001:db8::1]:8080/","http","//user@[2001:db8::1]:8080","[2001:db8::1]","8080","/",null,null,null},
       /*38*/ {"http://[2001:db8::1]/","http","//[2001:db8::1]","[2001:db8::1]",null,"/",null,null,null},
       /*39*/ {"//[2001:db8::1]:8080/",null,null,null,null,"//[2001:db8::1]:8080/",null,null,null},
       /*40*/ {"http://user@[2001:db8::1]:8080/","http","//user@[2001:db8::1]:8080","[2001:db8::1]","8080","/",null,null,null},
       /*41*/ {"*",null,null,null,null,"*",null, null,null}
    };
    
    
    public void testPathURIs()
        throws Exception
    {
        HttpURI uri = new HttpURI();
        
        for (int t=0;t<path_tests.length;t++)
        {
            uri.parse(path_tests[t][0].getBytes(),0,path_tests[t][0].length());
            assertEquals(t+" "+path_tests[t][0],path_tests[t][1],uri.getScheme());
            assertEquals(t+" "+path_tests[t][0],path_tests[t][2],uri.getAuthority());
            assertEquals(t+" "+path_tests[t][0],path_tests[t][3],uri.getHost());
            assertEquals(t+" "+path_tests[t][0],path_tests[t][4]==null?-1:Integer.parseInt(path_tests[t][4]),uri.getPort());
            assertEquals(t+" "+path_tests[t][0],path_tests[t][5],uri.getPath());
            assertEquals(t+" "+path_tests[t][0],path_tests[t][6],uri.getParam());
            assertEquals(t+" "+path_tests[t][0],path_tests[t][7],uri.getQuery());
            assertEquals(t+" "+path_tests[t][0],path_tests[t][8],uri.getFragment());
            assertEquals(path_tests[t][0], uri.toString());
        }
        
    }
    
    public void testInvalidAddress() throws Exception
    {
        assertInvalidURI("http://[ffff::1:8080/", "Invalid URL; no closing ']' -- should throw exception");
        assertInvalidURI("**", "only '*', not '**'");
        assertInvalidURI("*/", "only '*', not '*/'");
    }
    
    public void assertInvalidURI(String invalidURI, String message)
    {
        HttpURI uri = new HttpURI();
        try
        {
            uri.parse(invalidURI);
            fail(message);
        }
        catch (IllegalArgumentException e)
        {
            assertTrue(true);
        }
    }

    String[][] encoding_tests=
    { 
       /* 0*/ {"/path/info","/path/info"}, 
       /* 1*/ {"/path/%69nfo","/path/info"}, 
       /* 2*/ {"http://host/path/%69nfo","/path/info"}, 
       /* 3*/ {"http://host/path/%69nf%c2%a4","/path/inf\u00a4"}, 
    };
    
    public void testEncoded()
    {

        HttpURI uri = new HttpURI();
        
        for (int t=0;t<encoding_tests.length;t++)
        {
            uri.parse(encoding_tests[t][0]);
            assertEquals(""+t,encoding_tests[t][1],uri.getDecodedPath());
            
        }
    }
}
