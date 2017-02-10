/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.elasticsearch.search.aggregations;

import org.elasticsearch.common.ParseField;
import org.elasticsearch.common.io.stream.NamedWriteable;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.common.util.BigArrays;
import org.elasticsearch.common.xcontent.ToXContent;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.rest.action.search.RestSearchAction;
import org.elasticsearch.script.ScriptService;
import org.elasticsearch.search.aggregations.pipeline.PipelineAggregator;
import org.elasticsearch.search.aggregations.support.AggregationPath;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * An internal implementation of {@link Aggregation}. Serves as a base class for all aggregation implementations.
 */
public abstract class InternalAggregation implements Aggregation, ToXContent, NamedWriteable {

    /** Delimiter used when prefixing aggregation names with their type using the typed_keys parameter **/
    public static final String TYPED_KEYS_DELIMITER = "#";

    public static class ReduceContext {

        private final BigArrays bigArrays;
        private final ScriptService scriptService;

        public ReduceContext(BigArrays bigArrays, ScriptService scriptService) {
            this.bigArrays = bigArrays;
            this.scriptService = scriptService;
        }

        public BigArrays bigArrays() {
            return bigArrays;
        }

        public ScriptService scriptService() {
            return scriptService;
        }
    }

    protected final String name;

    protected final Map<String, Object> metaData;

    private final List<PipelineAggregator> pipelineAggregators;

    /**
     * Constructs an get with a given name.
     *
     * @param name The name of the get.
     */
    protected InternalAggregation(String name, List<PipelineAggregator> pipelineAggregators, Map<String, Object> metaData) {
        this.name = name;
        this.pipelineAggregators = pipelineAggregators;
        this.metaData = metaData;
    }

    /**
     * Read from a stream.
     */
    protected InternalAggregation(StreamInput in) throws IOException {
        name = in.readString();
        metaData = in.readMap();
        pipelineAggregators = in.readNamedWriteableList(PipelineAggregator.class);
    }

    @Override
    public final void writeTo(StreamOutput out) throws IOException {
        out.writeString(name);
        out.writeGenericValue(metaData);
        out.writeNamedWriteableList(pipelineAggregators);
        doWriteTo(out);
    }

    protected abstract void doWriteTo(StreamOutput out) throws IOException;

    @Override
    public String getName() {
        return name;
    }

    /**
     * Reduces the given aggregations to a single one and returns it. In <b>most</b> cases, the assumption will be the all given
     * aggregations are of the same type (the same type as this aggregation). For best efficiency, when implementing,
     * try reusing an existing instance (typically the first in the given list) to save on redundant object
     * construction.
     */
    public final InternalAggregation reduce(List<InternalAggregation> aggregations, ReduceContext reduceContext) {
        InternalAggregation aggResult = doReduce(aggregations, reduceContext);
        for (PipelineAggregator pipelineAggregator : pipelineAggregators) {
            aggResult = pipelineAggregator.reduce(aggResult, reduceContext);
        }
        return aggResult;
    }

    public abstract InternalAggregation doReduce(List<InternalAggregation> aggregations, ReduceContext reduceContext);

    @Override
    public Object getProperty(String path) {
        AggregationPath aggPath = AggregationPath.parse(path);
        return getProperty(aggPath.getPathElementsAsStringList());
    }

    public abstract Object getProperty(List<String> path);

    /**
     * Read a size under the assumption that a value of 0 means unlimited.
     */
    protected static int readSize(StreamInput in) throws IOException {
        final int size = in.readVInt();
        return size == 0 ? Integer.MAX_VALUE : size;
    }

    /**
     * Write a size under the assumption that a value of 0 means unlimited.
     */
    protected static void writeSize(int size, StreamOutput out) throws IOException {
        if (size == Integer.MAX_VALUE) {
            size = 0;
        }
        out.writeVInt(size);
    }

    @Override
    public Map<String, Object> getMetaData() {
        return metaData;
    }

    public List<PipelineAggregator> pipelineAggregators() {
        return pipelineAggregators;
    }

    /**
     * Returns a string representing the type of the aggregation. This type is added to
     * the aggregation name in the response, so that it can later be used by REST clients
     * to determine the internal type of the aggregation.
     */
    protected String getType() {
        return getWriteableName();
    }

    @Override
    public final XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
        if (params.paramAsBoolean(RestSearchAction.TYPED_KEYS_PARAM, false)) {
            // Concatenates the type and the name of the aggregation (ex: top_hits#foo)
            builder.startObject(String.join(TYPED_KEYS_DELIMITER, getType(), getName()));
        } else {
            builder.startObject(getName());
        }
        if (this.metaData != null) {
            builder.field(CommonFields.META);
            builder.map(this.metaData);
        }
        doXContentBody(builder, params);
        builder.endObject();
        return builder;
    }

    public abstract XContentBuilder doXContentBody(XContentBuilder builder, Params params) throws IOException;

    /**
     * Common xcontent fields that are shared among addAggregation
     */
    public static final class CommonFields extends ParseField.CommonFields {
        // todo convert these to ParseField
        public static final String META = "meta";
        public static final String BUCKETS = "buckets";
        public static final String VALUE = "value";
        public static final String VALUES = "values";
        public static final String VALUE_AS_STRING = "value_as_string";
        public static final String DOC_COUNT = "doc_count";
        public static final String KEY = "key";
        public static final String KEY_AS_STRING = "key_as_string";
        public static final String FROM = "from";
        public static final String FROM_AS_STRING = "from_as_string";
        public static final String TO = "to";
        public static final String TO_AS_STRING = "to_as_string";
    }
}
