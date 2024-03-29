/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.commons.net.ssh.kex;

import java.math.BigInteger;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;

import javax.crypto.KeyAgreement;
import javax.crypto.spec.DHParameterSpec;
import javax.crypto.spec.DHPublicKeySpec;

import org.apache.commons.net.ssh.SSHRuntimeException;
import org.apache.commons.net.ssh.util.SecurityUtils;

/**
 * Diffie-Hellman key generator.
 */
public class DH
{
    
    private BigInteger p;
    private BigInteger g;
    private BigInteger e; // my public key
    private byte[] e_array;
    private BigInteger f; // your public key
    private BigInteger K; // shared secret key
    private byte[] K_array;
    private final KeyPairGenerator myKpairGen;
    private final KeyAgreement myKeyAgree;
    
    public DH()
    {
        try
        {
            myKpairGen = SecurityUtils.getKeyPairGenerator("DH");
            myKeyAgree = SecurityUtils.getKeyAgreement("DH");
        } catch (GeneralSecurityException e)
        {
            throw new SSHRuntimeException(e);
        }
        
    }
    
    public byte[] getE()
    {
        if (e == null)
        {
            DHParameterSpec dhSkipParamSpec = new DHParameterSpec(p, g);
            KeyPair myKpair;
            try
            {
                myKpairGen.initialize(dhSkipParamSpec);
                myKpair = myKpairGen.generateKeyPair();
                myKeyAgree.init(myKpair.getPrivate());
            } catch (GeneralSecurityException e)
            {
                throw new SSHRuntimeException(e);
            }
            e = ((javax.crypto.interfaces.DHPublicKey) myKpair.getPublic()).getY();
            e_array = e.toByteArray();
        }
        return e_array;
    }
    
    public byte[] getK()
    {
        if (K == null)
        {
            try
            {
                KeyFactory myKeyFac = SecurityUtils.getKeyFactory("DH");
                DHPublicKeySpec keySpec = new DHPublicKeySpec(f, p, g);
                PublicKey yourPubKey = myKeyFac.generatePublic(keySpec);
                myKeyAgree.doPhase(yourPubKey, true);
            } catch (GeneralSecurityException e)
            {
                throw new SSHRuntimeException(e);
            }
            byte[] mySharedSecret = myKeyAgree.generateSecret();
            K = new BigInteger(mySharedSecret);
            K_array = mySharedSecret;
        }
        return K_array;
    }
    
    public void setF(byte[] f)
    {
        setF(new BigInteger(f));
    }
    
    public void setG(byte[] g)
    {
        setG(new BigInteger(g));
    }
    
    public void setP(byte[] p)
    {
        setP(new BigInteger(p));
    }
    
    void setF(BigInteger f)
    {
        this.f = f;
    }
    
    void setG(BigInteger g)
    {
        this.g = g;
    }
    
    void setP(BigInteger p)
    {
        this.p = p;
    }
    
}
