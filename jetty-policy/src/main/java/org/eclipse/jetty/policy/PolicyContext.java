package org.eclipse.jetty.policy;

import java.security.KeyStore;

public class PolicyContext
{
    private PropertyEvaluator evaluator;
    
    private KeyStore keystore;

    public PolicyContext()
    {
        
    }
    
    public PolicyContext( PropertyEvaluator evaluator )
    {
        this.evaluator = evaluator;
    }
    
    public PropertyEvaluator getEvaluator()
    {
        return evaluator;
    }

    public void setEvaluator( PropertyEvaluator evaluator )
    {
        this.evaluator = evaluator;
    }

    public KeyStore getKeystore()
    {
        return keystore;
    }

    public void setKeystore( KeyStore keystores )
    {
        this.keystore = keystore;
    }  
}
