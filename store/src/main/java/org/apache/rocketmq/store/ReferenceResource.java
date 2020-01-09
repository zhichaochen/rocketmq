/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.rocketmq.store;

import java.util.concurrent.atomic.AtomicLong;

/**
 * MappedFile父类
 * 作用是记录MappedFile的引用数, 记录当前MappedFile
 *
 * 我的总结：记录当前正在操作该 MappedFile 文件的线程数。
 *      当写MappedFile的读写都会记录一下，
 *      比如：同步数据的时候，同步数据master是读取数据，但是也会release。
 *
 * 我的理解：可以多线写入MappedFile文件。
 */
public abstract class ReferenceResource {
    //引用数
    protected final AtomicLong refCount = new AtomicLong(1);
    //MappedFile 是否可用
    protected volatile boolean available = true;
    //是否清理干净
    protected volatile boolean cleanupOver = false;
    private volatile long firstShutdownTimestamp = 0;

    /**
     * 增加了一个引用（表示正在操作MappedFile文件）
     * 占用资源,refCount + 1
     * @return
     */
    public synchronized boolean hold() {
        if (this.isAvailable()) {
            if (this.refCount.getAndIncrement() > 0) {
                return true;
            } else {//不会出现
                this.refCount.getAndDecrement();
            }
        }

        return false;
    }

    /**
     * MappedFile是否可用
     * @return
     */
    public boolean isAvailable() {
        return this.available;
    }

    /**
     * 参数 intervalForcibly 代表强制间隔，即两次生效的间隔至少要有这么大(不是至多!!!)
     * 第一次调用时available设置为false，设置初始时间，释放一个引用
     * 之后再调用的时候，如果refCount > 0,且超过了强制间隔，则设置为一个负数，释放一个引用
     *
     * 备注：如果在intervalForcibly时间内再次shutdown 代码不会执行任何逻辑
     */
    public void shutdown(final long intervalForcibly) {
        if (this.available) {
            this.available = false;
            this.firstShutdownTimestamp = System.currentTimeMillis();
            this.release();
        } else if (this.getRefCount() > 0) {
            if ((System.currentTimeMillis() - this.firstShutdownTimestamp) >= intervalForcibly) {
                this.refCount.set(-1000 - this.getRefCount());
                this.release();
            }
        }
    }

    /**
     * 释放引用，和hold做相反的操作。
     */
    public void release() {
        long value = this.refCount.decrementAndGet();
        if (value > 0)
            return;

        synchronized (this) {

            this.cleanupOver = this.cleanup(value);
        }
    }

    public long getRefCount() {
        return this.refCount.get();
    }

    public abstract boolean cleanup(final long currentRef);

    public boolean isCleanupOver() {
        return this.refCount.get() <= 0 && this.cleanupOver;
    }
}
