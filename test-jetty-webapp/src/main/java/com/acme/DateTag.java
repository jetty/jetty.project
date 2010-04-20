//========================================================================
//Copyright 2004-2008 Mort Bay Consulting Pty. Ltd.
//------------------------------------------------------------------------
//Licensed under the Apache License, Version 2.0 (the "License");
//you may not use this file except in compliance with the License.
//You may obtain a copy of the License at 
//http://www.apache.org/licenses/LICENSE-2.0
//Unless required by applicable law or agreed to in writing, software
//distributed under the License is distributed on an "AS IS" BASIS,
//WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
//See the License for the specific language governing permissions and
//limitations under the License.
//========================================================================

package com.acme;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.TimeZone;

import javax.servlet.jsp.JspException;
import javax.servlet.jsp.JspTagException;
import javax.servlet.jsp.PageContext;
import javax.servlet.jsp.tagext.BodyContent;
import javax.servlet.jsp.tagext.BodyTagSupport;
import javax.servlet.jsp.tagext.Tag;

public class DateTag extends BodyTagSupport
{
    Tag parent;
    BodyContent body;
    String tz="GMT";

    public void setParent(Tag parent) {this.parent=parent;}
    public Tag getParent() {return parent;}
    public void setBodyContent(BodyContent content) {body=content;}
    public void setPageContext(PageContext pageContext) {}

    public void setTz(String value) {tz=value;}

    public int doStartTag() throws JspException {return EVAL_BODY_TAG;}
    
    public int doEndTag() throws JspException {return EVAL_PAGE;}

    public void doInitBody() throws JspException {}

    public int doAfterBody() throws JspException {
	try
	{
            SimpleDateFormat format = new SimpleDateFormat(body.getString());
            format.setTimeZone(TimeZone.getTimeZone(tz));
	    body.getEnclosingWriter().write(format.format(new Date()));
	    return SKIP_BODY;
	}
	catch (Exception ex) {
            ex.printStackTrace();
            throw new JspTagException(ex.toString());
	}
    }

    public void release()
    {
	body=null;
    }
}

