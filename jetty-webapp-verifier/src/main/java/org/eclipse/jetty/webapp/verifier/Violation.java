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

public class Violation
{
    private String path;

    private Severity severity;

    private String detail;

    private Throwable throwable;

    private String ruleId;

    private Class<? extends Rule> ruleClass;

    public Violation(Severity severity, String path, String detail)
    {
        this.severity = severity;
        this.path = path;
        this.detail = detail;
    }

    public Violation(Severity severity, String path, String detail, Rule verifier)
    {
        this.severity = severity;
        this.path = path;
        this.detail = detail;
        setVerifierInfo(verifier);
    }

    public Violation(Severity severity, String path, String detail, Throwable throwable)
    {
        this.severity = severity;
        this.path = path;
        this.detail = detail;
        this.throwable = throwable;
    }

    public void setVerifierInfo(Rule verifier)
    {
        this.ruleId = verifier.getName();
        this.ruleClass = verifier.getClass();
    }

    @Override
    public String toString()
    {
        StringBuffer msg = new StringBuffer();
        msg.append("Violation[");
        msg.append("severity=").append(severity.name());
        msg.append(",path=").append(path);
        msg.append(",detail=").append(detail);
        if (ruleId != null)
        {
            msg.append(",verifierId=").append(ruleId);
        }
        if (ruleClass != null)
        {
            msg.append(",verifierClass=").append(ruleClass.getName());
        }
        if (throwable != null)
        {
            msg.append(",throwable=").append(throwable.getClass().getName());
        }
        msg.append("]");
        return msg.toString();
    }

    public String toDelimString()
    {
        StringBuffer msg = new StringBuffer();
        msg.append(severity.name());
        msg.append("|").append(path);
        msg.append("|").append(detail);
        return msg.toString();
    }

    @Override
    public int hashCode()
    {
        final int prime = 31;
        int result = 1;
        result = prime * result + ((detail == null)?0:detail.hashCode());
        result = prime * result + ((path == null)?0:path.hashCode());
        result = prime * result + ((severity == null)?0:severity.hashCode());
        return result;
    }

    @Override
    public boolean equals(Object obj)
    {
        if (this == obj)
        {
            return true;
        }
        if (obj == null)
        {
            return false;
        }
        if (getClass() != obj.getClass())
        {
            return false;
        }
        Violation other = (Violation)obj;
        if (detail == null)
        {
            if (other.detail != null)
            {
                return false;
            }
        }
        else if (!detail.equals(other.detail))
        {
            return false;
        }
        if (path == null)
        {
            if (other.path != null)
            {
                return false;
            }
        }
        else if (!path.equals(other.path))
        {
            return false;
        }
        if (severity == null)
        {
            if (other.severity != null)
            {
                return false;
            }
        }
        else if (!severity.equals(other.severity))
        {
            return false;
        }
        return true;
    }

    public Class<? extends Rule> getRuleClass()
    {
        return ruleClass;
    }

    public String getRuleId()
    {
        return ruleId;
    }

    public String getDetail()
    {
        return detail;
    }

    public String getPath()
    {
        return path;
    }

    public Severity getSeverity()
    {
        return severity;
    }

    public Throwable getThrowable()
    {
        return throwable;
    }
}
