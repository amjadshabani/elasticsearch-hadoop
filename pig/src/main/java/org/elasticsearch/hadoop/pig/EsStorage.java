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
package org.elasticsearch.hadoop.pig;

import java.io.IOException;
import java.io.StringReader;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Properties;
import java.util.Set;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.Writable;
import org.apache.hadoop.mapreduce.InputFormat;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.OutputFormat;
import org.apache.hadoop.mapreduce.RecordReader;
import org.apache.hadoop.mapreduce.RecordWriter;
import org.apache.pig.Expression;
import org.apache.pig.LoadFunc;
import org.apache.pig.LoadMetadata;
import org.apache.pig.LoadPushDown;
import org.apache.pig.ResourceSchema;
import org.apache.pig.ResourceStatistics;
import org.apache.pig.StoreFuncInterface;
import org.apache.pig.StoreMetadata;
import org.apache.pig.backend.hadoop.executionengine.mapReduceLayer.JobControlCompiler;
import org.apache.pig.backend.hadoop.executionengine.mapReduceLayer.PigSplit;
import org.apache.pig.backend.hadoop.executionengine.physicalLayer.relationalOperators.POStore;
import org.apache.pig.data.Tuple;
import org.apache.pig.data.TupleFactory;
import org.apache.pig.impl.logicalLayer.FrontendException;
import org.apache.pig.impl.util.ObjectSerializer;
import org.apache.pig.impl.util.UDFContext;
import org.elasticsearch.hadoop.EsHadoopIllegalArgumentException;
import org.elasticsearch.hadoop.cfg.InternalConfigurationOptions;
import org.elasticsearch.hadoop.cfg.Settings;
import org.elasticsearch.hadoop.cfg.SettingsManager;
import org.elasticsearch.hadoop.mr.EsOutputFormat;
import org.elasticsearch.hadoop.rest.InitializationUtils;
import org.elasticsearch.hadoop.util.IOUtils;
import org.elasticsearch.hadoop.util.ObjectUtils;
import org.elasticsearch.hadoop.util.SettingsUtils;
import org.elasticsearch.hadoop.util.StringUtils;

/**
 * Pig storage for reading and writing data into an ElasticSearch index.
 * Uses the tuple implied schema to create the resulting JSON string sent to ElasticSearch.
 * <p/>
 * Typical usage is:
 *
 * <pre>
 * A = LOAD 'twitter/_search?q=kimchy' USING org.elasticsearch.hadoop.pig.ESStorage();
 * </pre>
 * <pre>
 * STORE A INTO '<index>' USING org.elasticsearch.hadoop.pig.ESStorage();
 * </pre>
 *
 * The ElasticSearch host/port can be specified through Hadoop properties (see package description)
 * or passed to the {@link #EsStorage(String...)} constructor.
 */
public class EsStorage extends LoadFunc implements LoadMetadata, LoadPushDown, StoreFuncInterface, StoreMetadata {

    private static final Log log = LogFactory.getLog(EsStorage.class);
    private final boolean trace = log.isTraceEnabled();

    private Properties properties;

    private String relativeLocation;
    private String signature;
    private ResourceSchema schema;
    private RecordReader<String, Map<?, ?>> reader;
    private RecordWriter<Object, Object> writer;
    private PigTuple pigTuple;

    private List<String> aliasesTupleNames;
    private boolean IS_ES_10;

    public EsStorage() {
        this(new String[0]);
    }

    public EsStorage(String... configuration) {
        if (!ObjectUtils.isEmpty(configuration)) {
            try {
                properties = new Properties();
                for (String string : configuration) {
                    // replace ; with line separators
                    properties.load(new StringReader(string));
                }
            } catch (IOException ex) {
                throw new EsHadoopIllegalArgumentException("Cannot parse options " + Arrays.toString(configuration), ex);
            }
        }
    }

    @Override
    public String relToAbsPathForStoreLocation(String location, Path curDir) throws IOException {
        return location;
    }

    @Override
    public void setStoreFuncUDFContextSignature(String signature) {
        this.signature = signature;
    }

    private Properties getUDFProperties() {
        return UDFContext.getUDFContext().getUDFProperties(getClass(), new String[] { signature });
    }

    @Override
    public void checkSchema(ResourceSchema s) throws IOException {
        Properties props = getUDFProperties();

        // save schema to back-end for JSON translation
        if (props.getProperty(ResourceSchema.class.getName()) == null) {
            // save the schema as String (used JDK serialization since toString() screws up the signature - see the testcase)
            props.setProperty(ResourceSchema.class.getName(), IOUtils.serializeToBase64(s));
        }
    }

    @Override
    public void setStoreLocation(String location, Job job) throws IOException {
        init(location, job, false);
    }

    private void init(String location, Job job, boolean read) {
        Settings settings = SettingsManager.loadFrom(job.getConfiguration()).merge(properties);

        settings = (read ? settings.setResourceRead(location) : settings.setResourceWrite(location));

        boolean changed = false;
        InitializationUtils.checkIdForOperation(settings);

        changed |= InitializationUtils.setValueWriterIfNotSet(settings, PigValueWriter.class, log);
        changed |= InitializationUtils.setValueReaderIfNotSet(settings, PigValueReader.class, log);
        changed |= InitializationUtils.setBytesConverterIfNeeded(settings, PigBytesConverter.class, log);
        changed |= InitializationUtils.setFieldExtractorIfNotSet(settings, PigFieldExtractor.class, log);

        IS_ES_10 = SettingsUtils.isEs10(settings);
    }

    @SuppressWarnings("unchecked")
    @Override
    public OutputFormat<Object, Map<Writable, Writable>> getOutputFormat() throws IOException {
        return new EsOutputFormat();
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    @Override
    public void prepareToWrite(RecordWriter writer) throws IOException {
        this.writer = writer;

        Properties props = getUDFProperties();
        String s = props.getProperty(ResourceSchema.class.getName());
        this.schema = IOUtils.deserializeFromBase64(s);
        this.pigTuple = new PigTuple(schema);
    }

    // TODO: make put more lenient (if the schema is not available just shove everything on the existing type or as a big charray)
    @Override
    public void putNext(Tuple t) throws IOException {
        pigTuple.setTuple(t);

        if (trace) {
            log.trace("Writing out tuple " + t);
        }
        try {
            writer.write(null, pigTuple);
        } catch (InterruptedException ex) {
            throw new EsHadoopIllegalArgumentException("interrupted", ex);
        }
    }

    @Override
    public void cleanupOnFailure(String location, Job job) throws IOException {
        // no special clean-up required
    }

    // added in Pig 11.x
    public void cleanupOnSuccess(String location, Job job) throws IOException {
        //no-op
    }

    //
    // Store metadata - kinda of useless due to its life-cycle
    //

    @Override
    public void storeStatistics(ResourceStatistics stats, String location, Job job) throws IOException {
        // no-op
    }

    @Override
    public void storeSchema(ResourceSchema schema, String location, Job job) throws IOException {
        // no-op
        // this method is called _after_ the data (instead of before) has been written, which makes it useless
    }


    //
    // LoadFunc
    //
    @SuppressWarnings("unchecked")
    public void setLocation(String location, Job job) throws IOException {
        init(location, job, true);

        Configuration cfg = job.getConfiguration();

        Settings settings = SettingsManager.loadFrom(cfg);
        IS_ES_10 = SettingsUtils.isEs10(settings);

        if (settings.getScrollFields() != null) {
            return;
        }

        String fields = getUDFProperties().getProperty(InternalConfigurationOptions.INTERNAL_ES_TARGET_FIELDS);
        if (fields != null) {
            if (log.isDebugEnabled()) {
                log.debug(String.format("Found field project [%s] in UDF properties", fields));
            }

            cfg.set(InternalConfigurationOptions.INTERNAL_ES_TARGET_FIELDS, fields);
            return;
        }

        if (log.isTraceEnabled()) {
            log.trace("No field projection specified, looking for existing stores...");
        }

        String mapValues = cfg.get(JobControlCompiler.PIG_MAP_STORES);
        String reduceValues = cfg.get(JobControlCompiler.PIG_REDUCE_STORES);

        List<POStore> mapStore = Collections.emptyList();
        List<POStore> reduceStore = Collections.emptyList();

        if (StringUtils.hasText(mapValues)) {
            mapStore = (List<POStore>) ObjectSerializer.deserialize(mapValues);
        }
        if (StringUtils.hasText(reduceValues)) {
            reduceStore = (List<POStore>) ObjectSerializer.deserialize(reduceValues);
        }
        if (mapStore.size() + reduceStore.size() > 1) {
            log.warn("Too many POstores - cannot properly determine Pig schema");
        }
        else if (mapStore.size() + reduceStore.size() == 0) {
            log.warn("No POstores - cannot properly determine Pig schema");
        }
        else {
            POStore store = (reduceStore.isEmpty() ? mapStore.get(0) : reduceStore.get(0));
            // no schema specified - load all fields (or the default)
            if (store.getSchema() == null) {
                if (log.isTraceEnabled()) {
                    log.trace(String.format("Store [%s] defines no schema; falling back to default projection", store));
                }
                return;
            }
            else {
                fields = PigUtils.asProjection(store.getSchema(), properties);
            }
            if (log.isDebugEnabled()) {
                log.debug(String.format("Found field projection [%s] in store %s", fields, store));
            }
            cfg.set(InternalConfigurationOptions.INTERNAL_ES_TARGET_FIELDS, fields);
            getUDFProperties().setProperty(InternalConfigurationOptions.INTERNAL_ES_TARGET_FIELDS, fields);
        }
    }


    @Override
    public String relativeToAbsolutePath(String location, Path curDir) throws IOException {
        // TODO: potentially do additional parsing here
        relativeLocation = location;
        return relativeLocation;
    }

    @SuppressWarnings("rawtypes")
    @Override
    public InputFormat getInputFormat() throws IOException {
        return new EsPigInputFormat();
    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    @Override
    public void prepareToRead(RecordReader reader, PigSplit split) throws IOException {
        this.reader = reader;
        aliasesTupleNames = StringUtils.tokenize(getUDFProperties().getProperty(
                InternalConfigurationOptions.INTERNAL_ES_TARGET_FIELDS));
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    @Override
    public Tuple getNext() throws IOException {
        try {
            if (!reader.nextKeyValue()) {
                return null;
            }

            Map dataMap = reader.getCurrentValue();
            Tuple tuple = TupleFactory.getInstance().newTuple(dataMap.size());

            if (dataMap.isEmpty()) {
                return tuple;
            }

            if (!aliasesTupleNames.isEmpty()) {
                for (int i = 0; i < aliasesTupleNames.size(); i++) {
                    if (IS_ES_10) {
                        Object result = dataMap;
                        // check for multi-level alias
                        for (String level : StringUtils.tokenize(aliasesTupleNames.get(i), ".")) {
                            result = ((Map) result).get(level);
                            if (result == null) {
                                break;
                            }
                        }
                        tuple.set(i, result);
                    }
                    // ES 0.90.x / fields
                    else {
                        tuple.set(i, dataMap.get(aliasesTupleNames.get(i)));
                    }
                }
            }
            else {
                int i = 0;
                Set<Entry<?, ?>> entrySet = dataMap.entrySet();
                for (Map.Entry entry : entrySet) {
                    tuple.set(i++, entry.getValue());
                }
            }

            if (trace) {
                log.trace("Reading out tuple " + tuple);
            }
            return tuple;

        } catch (InterruptedException ex) {
            throw new IOException("interrupted", ex);
        }
    }

    //
    // LoadPushDown
    //
    @Override
    public List<OperatorSet> getFeatures() {
        return Arrays.asList(LoadPushDown.OperatorSet.PROJECTION);
    }

    @Override
    public RequiredFieldResponse pushProjection(RequiredFieldList requiredFieldList) throws FrontendException {
        String fields = PigUtils.asProjection(requiredFieldList, properties);
        getUDFProperties().setProperty(InternalConfigurationOptions.INTERNAL_ES_TARGET_FIELDS, fields);
        if (log.isTraceEnabled()) {
            log.trace(String.format("Given push projection; saving field projection [%s]", fields));
        }
        return new RequiredFieldResponse(true);
    }

    @Override
    public ResourceSchema getSchema(String location, Job job) throws IOException {
        return null;
    }

    @Override
    public ResourceStatistics getStatistics(String location, Job job) throws IOException {
        return null;
    }

    @Override
    public String[] getPartitionKeys(String location, Job job) throws IOException {
        return null;
    }

    @Override
    public void setPartitionFilter(Expression partitionFilter) throws IOException {
        //
    }

    @Override
    public void setUDFContextSignature(String signature) {
        this.signature = signature;
    }
}