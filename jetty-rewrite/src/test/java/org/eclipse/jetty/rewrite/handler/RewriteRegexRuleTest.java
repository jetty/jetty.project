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
package org.eclipse.jetty.rewrite.handler;

import java.io.IOException;

import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class RewriteRegexRuleTest extends AbstractRuleTestCase
{
    private String[][] _tests=
    {
            {"/foo/bar",null,".*","/replace","/replace",null},
            {"/foo/bar","n=v",".*","/replace","/replace","n=v"},
            {"/foo/bar",null,"/xxx.*","/replace",null,null},
            {"/foo/bar",null,"/(.*)/(.*)","/$2/$1/xxx","/bar/foo/xxx",null},
            {"/foo/bar",null,"/(.*)/(.*)","/test?p2=$2&p1=$1","/test","p2=bar&p1=foo"},
            {"/foo/bar","n=v","/(.*)/(.*)","/test?p2=$2&p1=$1","/test","n=v&p2=bar&p1=foo"},
            {"/foo/bar",null,"/(.*)/(.*)","/foo/bar?p2=$2&p1=$1","/foo/bar","p2=bar&p1=foo"},
            {"/foo/bar","n=v","/(.*)/(.*)","/foo/bar?p2=$2&p1=$1","/foo/bar","n=v&p2=bar&p1=foo"},
            {"/foo/bar",null,"/(foo)/(.*)(bar)","/$3/$1/xxx$2","/bar/foo/xxx",null},
            {"/foo/$bar",null,".*","/$replace","/$replace",null},
            {"/foo/$bar",null,"/foo/(.*)","/$1/replace","/$bar/replace",null},
            {"/foo/bar/info",null,"/foo/(NotHere)?([^/]*)/(.*)","/$3/other?p1=$2","/info/other","p1=bar"},
            {"/foo/bar/info",null,"/foo/(NotHere)?([^/]*)/(.*)","/$3/other?p1=$2&$Q","/info/other","p1=bar&"},
            {"/foo/bar/info","n=v","/foo/(NotHere)?([^/]*)/(.*)","/$3/other?p1=$2&$Q","/info/other","p1=bar&n=v"},
            {"/foo/bar/info","n=v","/foo/(NotHere)?([^/]*)/(.*)","/$3/other?p1=$2","/info/other","n=v&p1=bar"},
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
            String t=test[0]+"?"+test[1]+">"+test[2]+"|"+test[3];
            _rule.setRegex(test[2]);
            _rule.setReplacement(test[3]);

            _request.setRequestURI(test[0]);
            _request.setQueryString(test[1]);
            _request.getAttributes().clearAttributes();
            
            String result = _rule.matchAndApply(test[0], _request, _response);
            assertEquals(t, test[4], result);
            _rule.applyURI(_request,test[0],result);

            assertEquals(t,test[4], _request.getRequestURI());
            assertEquals(t,test[5], _request.getQueryString());
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
            String t=test[0]+"?"+test[1]+">"+test[2]+"|"+test[3];
            _rule.setRegex(test[2]);
            _rule.setReplacement(test[3]);

            _request.setRequestURI(test[0]);
            _request.setQueryString(test[1]);
            _request.getAttributes().clearAttributes();
            
            String result = container.apply(test[0],_request,_response);
            assertEquals(t,test[4]==null?test[0]:test[4], result);
            assertEquals(t,test[4]==null?test[0]:test[4], _request.getRequestURI());
            assertEquals(t,test[5], _request.getQueryString());
        }
    }
}
