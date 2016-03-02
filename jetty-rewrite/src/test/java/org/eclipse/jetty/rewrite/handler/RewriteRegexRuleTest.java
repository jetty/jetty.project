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

package org.eclipse.jetty.rewrite.handler;

import static org.junit.Assert.assertEquals;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.eclipse.jetty.util.MultiMap;
import org.eclipse.jetty.util.URIUtil;
import org.eclipse.jetty.util.UrlEncoded;
import org.junit.Before;
import org.junit.Test;

public class RewriteRegexRuleTest extends AbstractRuleTestCase
{
    private String[][] _tests=
    {
            {"/foo0/bar",null,".*","/replace","/replace",null},
            {"/foo1/bar","n=v",".*","/replace","/replace","n=v"},
            {"/foo2/bar",null,"/xxx.*","/replace",null,null},
            {"/foo3/bar",null,"/(.*)/(.*)","/$2/$1/xxx","/bar/foo3/xxx",null},
            {"/f%20o3/bar",null,"/(.*)/(.*)","/$2/$1/xxx","/bar/f%20o3/xxx",null},
            {"/foo4/bar",null,"/(.*)/(.*)","/test?p2=$2&p1=$1","/test","p2=bar&p1=foo4"},
            {"/foo5/bar","n=v","/(.*)/(.*)","/test?p2=$2&p1=$1","/test","n=v&p2=bar&p1=foo5"},
            {"/foo6/bar",null,"/(.*)/(.*)","/foo6/bar?p2=$2&p1=$1","/foo6/bar","p2=bar&p1=foo6"},
            {"/foo7/bar","n=v","/(.*)/(.*)","/foo7/bar?p2=$2&p1=$1","/foo7/bar","n=v&p2=bar&p1=foo7"},
            {"/foo8/bar",null,"/(foo8)/(.*)(bar)","/$3/$1/xxx$2","/bar/foo8/xxx",null},
            {"/foo9/$bar",null,".*","/$replace","/$replace",null},
            {"/fooA/$bar",null,"/fooA/(.*)","/$1/replace","/$bar/replace",null},
            {"/fooB/bar/info",null,"/fooB/(NotHere)?([^/]*)/(.*)","/$3/other?p1=$2","/info/other","p1=bar"},
            {"/fooC/bar/info",null,"/fooC/(NotHere)?([^/]*)/(.*)","/$3/other?p1=$2&$Q","/info/other","p1=bar&"},
            {"/fooD/bar/info","n=v","/fooD/(NotHere)?([^/]*)/(.*)","/$3/other?p1=$2&$Q","/info/other","p1=bar&n=v"},
            {"/fooE/bar/info","n=v","/fooE/(NotHere)?([^/]*)/(.*)","/$3/other?p1=$2","/info/other","n=v&p1=bar"},
    };
    private RewriteRegexRule _rule;

    @Before
    public void init() throws Exception
    {
        start(false);
        _rule=new RewriteRegexRule();
    }

    @Test
    public void testRequestUriEnabled() throws IOException
    {
        for (String[] test : _tests)
        {
            reset();
            _request.setURIPathQuery(null);
            
            String t=test[0]+"?"+test[1]+">"+test[2]+"|"+test[3];
            _rule.setRegex(test[2]);
            _rule.setReplacement(test[3]);

            _request.setURIPathQuery(test[0]+(test[1]==null?"":("?"+test[1])));
            
            String result = _rule.matchAndApply(test[0], _request, _response);
            assertEquals(t, test[4], result);
            _rule.applyURI(_request,test[0],result);

            if (result!=null)
            {
                assertEquals(t,test[4], _request.getRequestURI());
                assertEquals(t,test[5], _request.getQueryString());
            }
            
            if (test[5]!=null)
            {
                MultiMap<String> params=new MultiMap<String>();
                UrlEncoded.decodeTo(test[5],params, StandardCharsets.UTF_8);
                               
                for (String n:params.keySet())
                    assertEquals(params.getString(n),_request.getParameter(n));
            }
        }
    }
    
    @Test
    public void testContainedRequestUriEnabled() throws IOException
    {
        RuleContainer container = new RuleContainer();
        container.setRewriteRequestURI(true);
        container.addRule(_rule);
        for (String[] test : _tests)
        {
            reset();
            String t=test[0]+"?"+test[1]+">"+test[2]+"|"+test[3];
            _rule.setRegex(test[2]);
            _rule.setReplacement(test[3]);

            _request.setURIPathQuery(test[0]);
            _request.setQueryString(test[1]);
            _request.getAttributes().clearAttributes();
            
            String result = container.apply(URIUtil.decodePath(test[0]),_request,_response);
            assertEquals(t,URIUtil.decodePath(test[4]==null?test[0]:test[4]), result);
            assertEquals(t,test[4]==null?test[0]:test[4], _request.getRequestURI());
            assertEquals(t,test[5], _request.getQueryString());
        }
    }
}
