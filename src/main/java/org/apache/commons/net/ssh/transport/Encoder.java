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
package org.apache.commons.net.ssh.transport;

import java.util.concurrent.locks.Lock;

import org.apache.commons.net.ssh.SSHPacket;
import org.apache.commons.net.ssh.cipher.Cipher;
import org.apache.commons.net.ssh.compression.Compression;
import org.apache.commons.net.ssh.mac.MAC;
import org.apache.commons.net.ssh.random.Random;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Encodes packets into the SSH binary protocol per the current algorithms.
 */
final class Encoder extends Converter
{
    
    private final Logger log = LoggerFactory.getLogger(getClass());
    
    private final Random prng;
    
    private final Lock encodeLock;
    
    Encoder(Random prng, Lock encodeLock)
    {
        this.prng = prng;
        this.encodeLock = encodeLock;
    }
    
    private SSHPacket checkHeaderSpace(SSHPacket buffer)
    {
        if (buffer.rpos() < 5)
        {
            log.warn("Performance cost: when sending a packet, ensure that "
                    + "5 bytes are available in front of the buffer");
            SSHPacket nb = new SSHPacket(buffer.available() + 5);
            nb.rpos(5);
            nb.wpos(5);
            nb.putBuffer(buffer);
            buffer = nb;
        }
        return buffer;
    }
    
    private void compress(SSHPacket buffer) throws TransportException
    {
        // Compress the packet if needed
        if (compression != null && (authed || !compression.isDelayed()))
            compression.compress(buffer);
    }
    
    private void putMAC(SSHPacket buffer, int startOfPacket, int endOfPadding)
    {
        if (mac != null)
        {
            buffer.wpos(endOfPadding + mac.getBlockSize());
            mac.update(seq);
            mac.update(buffer.array(), startOfPacket, endOfPadding);
            mac.doFinal(buffer.array(), endOfPadding);
        }
    }
    
    /**
     * Encode a buffer into the SSH binary protocol per the current algorithms.
     * 
     * @param buffer
     *            the buffer to encode
     * @return the sequence no. of encoded packet
     * @throws TransportException
     */
    long encode(SSHPacket buffer) throws TransportException
    {
        encodeLock.lock();
        try
        {
            buffer = checkHeaderSpace(buffer);
            
            if (log.isTraceEnabled())
                log.trace("Encoding packet #{}: {}", seq, buffer.printHex());
            
            compress(buffer);
            
            final int payloadSize = buffer.available();
            
            // Compute padding length
            int padLen = -(payloadSize + 5) & cipherSize - 1;
            if (padLen < cipherSize)
                padLen += cipherSize;
            
            final int startOfPacket = buffer.rpos() - 5;
            final int packetLen = payloadSize + 1 + padLen;
            
            // Put packet header
            buffer.wpos(startOfPacket);
            buffer.putInt(packetLen);
            buffer.putByte((byte) padLen);
            
            // Now wpos will mark end of padding
            buffer.wpos(startOfPacket + 5 + payloadSize + padLen);
            // Fill padding
            prng.fill(buffer.array(), buffer.wpos() - padLen, padLen);
            
            seq = seq + 1 & 0xffffffffL;
            
            putMAC(buffer, startOfPacket, buffer.wpos());
            
            cipher.update(buffer.array(), startOfPacket, 4 + packetLen);
            
            buffer.rpos(startOfPacket); // Make ready-to-read
            
            return seq;
        } finally
        {
            encodeLock.unlock();
        }
    }
    
    @Override
    void setAlgorithms(Cipher cipher, MAC mac, Compression compression)
    {
        encodeLock.lock();
        try
        {
            super.setAlgorithms(cipher, mac, compression);
            if (compression != null)
                compression.init(Compression.Type.Deflater, -1);
        } finally
        {
            encodeLock.unlock();
        }
    }
    
    @Override
    void setAuthenticated()
    {
        encodeLock.lock();
        try
        {
            super.setAuthenticated();
        } finally
        {
            encodeLock.unlock();
        }
    }
    
}