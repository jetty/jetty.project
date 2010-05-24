// ========================================================================
// Copyright (c) 2006-2010 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.webapp;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.xml.XmlParser;



/**
 * Descriptor
 *
 * A web descriptor (web.xml/web-defaults.xml/web-overrides.xml).
 */
public class Descriptor
{
    public enum MetaDataComplete {NotSet, True, False};
    protected Resource _xml;
    protected XmlParser.Node _root;
    protected MetaDataComplete _metaDataComplete;
    protected int _majorVersion = 3; //default to container version
    protected int _minorVersion = 0;
    protected ArrayList<String> _classNames;
    protected boolean _distributable;
    protected boolean _validating;
    protected MetaDataProcessor _processor;
    protected boolean _isOrdered = false;
    protected List<String> _ordering = new ArrayList<String>();
    

    
    
    public Descriptor (Resource xml, MetaDataProcessor processor)
    {
        _xml = xml;
        _processor = processor;
    }
    
    public void parse ()
    throws Exception
    {
        if (_root == null)
        {
            //boolean oldValidating = _processor.getParser().getValidating();
            //_processor.getParser().setValidating(_validating);
            _root = _processor.getParser().parse(_xml.getURL().toString());
            processVersion();
            processOrdering();
            //_processor.getParser().setValidating(oldValidating);
        }
    }
    
    public MetaDataComplete getMetaDataComplete()
    {
        return _metaDataComplete;
    }
    
    
    public XmlParser.Node getRoot ()
    {
        return _root;
    }
    
    public int getMajorVersion ()
    {
        return _majorVersion;
    }
    
    public int getMinorVersion()
    {
        return _minorVersion;
    }
    
    public Resource getResource ()
    {
        return _xml;
    }
    
    public void processVersion ()
    {
        String version = _root.getAttribute("version", "DTD");
        if ("DTD".equals(version))
        {
            _majorVersion = 2;
            _minorVersion = 3;
            String dtd = _processor.getParser().getDTD();
            if (dtd != null && dtd.indexOf("web-app_2_2") >= 0)
            {
                _majorVersion = 2;
                _minorVersion = 2;
            }
        }
        else 
        {
           int dot = version.indexOf(".");
           if (dot > 0)
           {
               _majorVersion = Integer.parseInt(version.substring(0,dot));
               _minorVersion = Integer.parseInt(version.substring(dot+1));
           }
        }
     
        if (_majorVersion < 2 && _minorVersion < 5)
            _metaDataComplete = MetaDataComplete.True; // does not apply before 2.5
        else
        {
            String s = (String)_root.getAttribute("metadata-complete");
            if (s == null)
                _metaDataComplete = MetaDataComplete.NotSet;
            else
                _metaDataComplete = Boolean.valueOf(s).booleanValue()?MetaDataComplete.True:MetaDataComplete.False;
        }
            
        Log.debug(_xml.toString()+": Calculated metadatacomplete = " + _metaDataComplete + " with version=" + version);     
    }
    
    public void processOrdering ()
    {
        //Process the web.xml's optional <absolute-ordering> element              
        XmlParser.Node ordering = _root.get("absolute-ordering");
        if (ordering == null)
           return; // could be a RelativeOrdering if we find any <ordering> clauses in web-fragments
        
        _isOrdered = true;
        //If an absolute-ordering was already set, then ignore it in favour of this new one
       // _processor.setOrdering(new AbsoluteOrdering());
   
        Iterator iter = ordering.iterator();
        XmlParser.Node node = null;
        while (iter.hasNext())
        {
            Object o = iter.next();
            if (!(o instanceof XmlParser.Node)) continue;
            node = (XmlParser.Node) o;

            if (node.getTag().equalsIgnoreCase("others"))
                //((AbsoluteOrdering)_processor.getOrdering()).addOthers();
                _ordering.add("others");
            else if (node.getTag().equalsIgnoreCase("name"))
                //((AbsoluteOrdering)_processor.getOrdering()).add(node.toString(false,true));
                _ordering.add(node.toString(false,true));
        }
    }
       
    public void processClassNames ()
    {
        _classNames = new ArrayList<String>();          
        Iterator iter = _root.iterator();

        while (iter.hasNext())
        {
            Object o = iter.next();
            if (!(o instanceof XmlParser.Node)) continue;
            XmlParser.Node node = (XmlParser.Node) o;
            String name = node.getTag();
            if ("servlet".equals(name))
            {
                String className = node.getString("servlet-class", false, true);
                if (className != null)
                    _classNames.add(className);
            }
            else if ("filter".equals(name))
            {
                String className = node.getString("filter-class", false, true);
                if (className != null)
                    _classNames.add(className);
            }
            else if ("listener".equals(name))
            {
                String className = node.getString("listener-class", false, true);
                if (className != null)
                    _classNames.add(className);
            }                    
        }
    }
    
    public ArrayList<String> getClassNames ()
    {
        return _classNames;
    }
    
    public void setDistributable (boolean distributable)
    {
        _distributable = distributable;
    }
    
    public boolean isDistributable()
    {
        return _distributable;
    }
    
    public void setValidating (boolean validating)
    {
       _validating = validating;
    }
    
    
    public boolean isOrdered()
    {
        return _isOrdered;
    }
    
    public List<String> getOrdering()
    {
        return _ordering;
    }
}
