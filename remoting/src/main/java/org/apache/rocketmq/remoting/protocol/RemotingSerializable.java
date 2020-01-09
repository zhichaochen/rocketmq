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
package org.apache.rocketmq.remoting.protocol;

import com.alibaba.fastjson.JSON;
import java.nio.charset.Charset;

/**
 * 序列化操作（使用json序列化的方式）
 *
 * 默认序列化的是[RemotingCommand]对象
 */
public abstract class RemotingSerializable {
    //默认的编码格式
    private final static Charset CHARSET_UTF8 = Charset.forName("UTF-8");

    /**
     * 编码：将请求头信息对象转换成json字符串
     * @param obj ：RemotingCommand
     * @return
     */
    public static byte[] encode(final Object obj) {
        final String json = toJson(obj, false);
        if (json != null) {
            return json.getBytes(CHARSET_UTF8);
        }
        return null;
    }

    public static String toJson(final Object obj, boolean prettyFormat) {
        return JSON.toJSONString(obj, prettyFormat);
    }

    /**
     * 解码，将json字符串，转化成对象。
     * @param data
     * @param classOfT
     * @param <T>
     * @return
     */
    public static <T> T decode(final byte[] data, Class<T> classOfT) {
        //字节码转化成字符串
        final String json = new String(data, CHARSET_UTF8);
        return fromJson(json, classOfT);
    }

    /**
     * 将json字符串转化为对象。
     *
     * @param json
     * @param classOfT
     * @param <T>
     * @return
     */
    public static <T> T fromJson(String json, Class<T> classOfT) {
        return JSON.parseObject(json, classOfT);
    }

    public byte[] encode() {
        final String json = this.toJson();
        if (json != null) {
            return json.getBytes(CHARSET_UTF8);
        }
        return null;
    }

    public String toJson() {
        return toJson(false);
    }

    public String toJson(final boolean prettyFormat) {
        return toJson(this, prettyFormat);
    }
}
