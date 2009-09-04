// ========================================================================
// Copyright (c) Webtide LLC
// ------------------------------------------------------------------------
// All rights reserved. This program and the accompanying materials
// are made available under the terms of the Eclipse Public License v1.0
// and Apache License v2.0 which accompanies this distribution.
//
// The Eclipse Public License is available at
// http://www.eclipse.org/legal/epl-v10.html
//
// The Apache License v2.0 is available at
// http://www.apache.org/licenses/LICENSE-2.0.txt
//
// You may elect to redistribute this code under either of these licenses.
// ========================================================================
package org.eclipse.jetty.webapp.verifier;

import java.io.File;
import java.net.URI;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.eclipse.jetty.xml.XmlConfiguration;

/**
 * RuleSet holds the set of configured {@link Rule}s that the WebappVerifier will use.
 */
public class RuleSet
{
    private String name;
    private List<Rule> rules = new ArrayList<Rule>();

    public String getName()
    {
        return name;
    }

    public void setName(String name)
    {
        this.name = name;
    }

    public List<Rule> getRules()
    {
        return rules;
    }

    public void setRules(List<Rule> rules)
    {
        this.rules = rules;
    }

    public void setRules(Rule[] ruleArray)
    {
        this.rules.clear();
        this.rules.addAll(Arrays.asList(ruleArray));
    }

    public void addRule(Rule rule)
    {
        this.rules.add(rule);
    }

    public WebappVerifier createWebappVerifier(URI webappURI)
    {
        WebappVerifier webappVerifier = new WebappVerifier(webappURI);
        webappVerifier.setRules(rules);
        return webappVerifier;
    }

    public static RuleSet load(URL configuration) throws Exception
    {
        XmlConfiguration xml;
        xml = new XmlConfiguration(configuration);
        return (RuleSet)xml.configure();
    }

    public static RuleSet load(URI configuration) throws Exception
    {
        return load(configuration.toURL());
    }

    public static RuleSet load(File configuration) throws Exception
    {
        return load(configuration.toURL());
    }
}
