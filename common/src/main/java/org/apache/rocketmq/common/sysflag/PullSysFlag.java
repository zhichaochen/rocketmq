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
package org.apache.rocketmq.common.sysflag;

/**
 * 【拉取消息】相关的【系统标志】
 */
public class PullSysFlag {
    //commit offset 标志 ：1
    private final static int FLAG_COMMIT_OFFSET = 0x1;
    //suspend ：暂停，暂停标志 ：2
    private final static int FLAG_SUSPEND = 0x1 << 1;
    //subscription 标志 ：4
    private final static int FLAG_SUBSCRIPTION = 0x1 << 2;
    //class 过滤 标志 ：8
    private final static int FLAG_CLASS_FILTER = 0x1 << 3;

    /**
     * 构建系统标志
     *
     * 计算系统标志：
     *
     * @param commitOffset ：偏移量
     * @param suspend ：是否暂停
     * @param subscription ：是否订阅
     * @param classFilter ：
     * @return
     */
    public static int buildSysFlag(final boolean commitOffset, final boolean suspend,
        final boolean subscription, final boolean classFilter) {

        //特别注意：开始值等于0，不代表计算之后还等于0;
        int flag = 0;

        if (commitOffset) {
            //0 + 1
            flag |= FLAG_COMMIT_OFFSET;
        }

        if (suspend) {
            //01 |= 10
            flag |= FLAG_SUSPEND;
        }

        if (subscription) {
            flag |= FLAG_SUBSCRIPTION;
        }

        if (classFilter) {
            flag |= FLAG_CLASS_FILTER;
        }

        return flag;
    }

    /**
     * 减去 FLAG_COMMIT_OFFSET 的值
     * @param sysFlag
     * @return
     */
    public static int clearCommitOffsetFlag(final int sysFlag) {
        return sysFlag & (~FLAG_COMMIT_OFFSET);
    }

    /**
     * 判断是否存在FLAG_COMMIT_OFFSET标志
     * @param sysFlag
     * @return
     */
    public static boolean hasCommitOffsetFlag(final int sysFlag) {
        return (sysFlag & FLAG_COMMIT_OFFSET) == FLAG_COMMIT_OFFSET;
    }

    /**
     * 是否存在FLAG_SUSPEND标志
     * @param sysFlag
     * @return
     */
    public static boolean hasSuspendFlag(final int sysFlag) {
        return (sysFlag & FLAG_SUSPEND) == FLAG_SUSPEND;
    }

    /**
     * 是否存在FLAG_SUBSCRIPTION标志
     * @param sysFlag
     * @return
     */
    public static boolean hasSubscriptionFlag(final int sysFlag) {
        return (sysFlag & FLAG_SUBSCRIPTION) == FLAG_SUBSCRIPTION;
    }

    /**
     * 是否存在FLAG_CLASS_FILTER标志
     * @param sysFlag
     * @return
     */
    public static boolean hasClassFilterFlag(final int sysFlag) {
        return (sysFlag & FLAG_CLASS_FILTER) == FLAG_CLASS_FILTER;
    }
}
