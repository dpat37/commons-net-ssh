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
package org.apache.commons.net.ssh.connection;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author <a href="mailto:dev@mina.apache.org">Apache MINA SSHD Project</a>
 */
public abstract class Window
{
    
    protected final Logger log;
    
    protected final Channel chan;
    
    protected int size;
    protected int initSize;
    protected int maxPacketSize;
    
    Window(Channel chan, boolean local)
    {
        this.chan = chan;
        log = LoggerFactory.getLogger("<< chan#" + chan.getID() + " / " + (local ? "local" : "remote") + " window >>");
    }
    
    public synchronized void consume(int dec)
    {
        size -= dec;
        if (log.isDebugEnabled())
            log.debug("Consuming by " + dec + " down to " + size);
    }
    
    public synchronized void expand(int inc)
    {
        size += inc;
        if (log.isDebugEnabled())
            log.debug("Increasing by {} up to {}", inc, size);
        notifyAll();
    }
    
    public synchronized int getInitialSize()
    {
        return initSize;
    }
    
    public synchronized int getMaxPacketSize()
    {
        return maxPacketSize;
    }
    
    public synchronized int getSize()
    {
        return size;
    }
    
    @Override
    public String toString()
    {
        return "[ size=" + size + " | maxPacketSize=" + maxPacketSize + "]";
    }
    
    void init(int initialWinSize, int maxPacketSize)
    {
        this.size = this.initSize = initialWinSize;
        this.maxPacketSize = maxPacketSize;
    }
    
}