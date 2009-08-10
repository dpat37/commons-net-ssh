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
package org.apache.commons.net.ssh.prng;

import java.security.SecureRandom;

import org.apache.commons.net.ssh.NamedFactory;
import org.bouncycastle.crypto.prng.RandomGenerator;
import org.bouncycastle.crypto.prng.VMPCRandomGenerator;

/**
 * BouncyCastle <code>Random</code>. This pseudo random number generator uses the a very fast PRNG
 * from BouncyCastle. The JRE random will be used when creating a new generator to add some random
 * data to the seed.
 * 
 * @author <a href="mailto:dev@mina.apache.org">Apache MINA SSHD Project</a>
 */
public class BouncyCastleRandom implements PRNG
{
    
    /**
     * Named factory for the BouncyCastle <code>Random</code>
     */
    public static class Factory implements NamedFactory<PRNG>
    {
        
        public PRNG create()
        {
            return new BouncyCastleRandom();
        }
        
        public String getName()
        {
            return "bouncycastle";
        }
        
    }
    
    private final RandomGenerator random;
    
    public BouncyCastleRandom()
    {
        random = new VMPCRandomGenerator();
        byte[] seed = new SecureRandom().generateSeed(8);
        random.addSeedMaterial(seed);
    }
    
    public void fill(byte[] bytes, int start, int len)
    {
        random.nextBytes(bytes, start, len);
    }
    
}