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
package org.apache.rocketmq.common.constant;

/**
 * permission ：许可，表示权限限定
 * 总结：0x：表示16进制
 *
 * PermName.PERM_READ | PermName.PERM_WRITE;：表示读写权限
 * 100 | 010 = 110 = 12
 */
public class PermName {
    //优先级，1000 = 8
    public static final int PERM_PRIORITY = 0x1 << 3;
    //读权限，100 = 4
    public static final int PERM_READ = 0x1 << 2;
    //写权限，10 = 2
    public static final int PERM_WRITE = 0x1 << 1;
    //继承，（inherit），1 = 1，表示可执行的权限吧？
    public static final int PERM_INHERIT = 0x1 << 0;

    /**
     * 将权限转换成字符串，其中：
     * 读：R--
     * 写：-W-
     * 执行：--X
     * @param perm
     * @return
     */
    public static String perm2String(final int perm) {
        final StringBuffer sb = new StringBuffer("---");
        if (isReadable(perm)) {
            sb.replace(0, 1, "R");
        }

        if (isWriteable(perm)) {
            sb.replace(1, 2, "W");
        }

        if (isInherited(perm)) {
            sb.replace(2, 3, "X");
        }

        return sb.toString();
    }

    /**
     * 是否可读
     * @param perm
     * @return
     */
    public static boolean isReadable(final int perm) {
        //判断条件，进行&之后，等于PERM_READ，表示正确。
        return (perm & PERM_READ) == PERM_READ;
    }

    /**
     * 是否可写
     * @param perm
     * @return
     */
    public static boolean isWriteable(final int perm) {
        return (perm & PERM_WRITE) == PERM_WRITE;
    }

    /**
     * 是否可执行
     * @param perm
     * @return
     */
    public static boolean isInherited(final int perm) {
        return (perm & PERM_INHERIT) == PERM_INHERIT;
    }
}
