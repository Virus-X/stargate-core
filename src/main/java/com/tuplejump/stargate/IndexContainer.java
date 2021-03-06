/*
 * Copyright 2014, Tuplejump Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.tuplejump.stargate;

import com.tuplejump.stargate.lucene.BasicIndexer;
import com.tuplejump.stargate.lucene.Indexer;
import com.tuplejump.stargate.lucene.SearcherCallback;
import org.apache.cassandra.db.DecoratedKey;
import org.apache.cassandra.dht.Range;
import org.apache.cassandra.dht.Token;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.MultiReader;
import org.apache.lucene.search.IndexSearcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * User: satya
 */
public class IndexContainer {
    protected static final Logger logger = LoggerFactory.getLogger(RowIndex.class);
    static ExecutorService executorService = Executors.newFixedThreadPool(10);
    Map<Range<Token>, Indexer> indexers = new HashMap<>();
    private ReadWriteLock indexLock = new ReentrantReadWriteLock();
    private final Lock writeLock = indexLock.writeLock();
    Analyzer analyzer;
    String keyspace;
    String cf;
    String indexName;

    public IndexContainer(Analyzer analyzer, String keyspace, String cf, String indexName) {
        indexers = new HashMap<>();
        this.analyzer = analyzer;
        this.keyspace = keyspace;
        this.cf = cf;
        this.indexName = indexName;
    }

    public void updateIndexers(Collection<Range<Token>> ranges) {
        writeLock.lock();
        try {
            if (indexers.isEmpty()) {
                logger.warn("Adding VNode indexers");
                for (Range<Token> range : ranges) {
                    Indexer indexer = new BasicIndexer(analyzer, keyspace, cf, indexName, range.left.toString());
                    indexers.put(range, indexer);
                    logger.warn("Added VNode indexers for range {}", range);
                }
            } else {
                logger.warn("Change in VNode indexers");
                HashMap<Range<Token>, Indexer> indexersToRemove = new HashMap<>(indexers);
                for (Range<Token> range : ranges) {
                    indexersToRemove.remove(range);
                }
                for (Map.Entry<Range<Token>, Indexer> entry : indexersToRemove.entrySet()) {
                    logger.warn("Removing indexer for range {}", entry.getKey());
                    Indexer indexer = indexers.remove(entry.getKey());
                    indexer.removeIndex();
                    logger.warn("Removed indexer for range {}", entry.getKey());
                }
            }
        } finally {
            writeLock.unlock();
        }
    }

    public <T> T search(SearcherCallback<T> searcherCallback) {
        List<IndexReader> indexReaders = new ArrayList<>();
        Map<Indexer, IndexSearcher> indexSearchers = new HashMap<>();
        for (Map.Entry<Range<Token>, Indexer> entry : indexers.entrySet()) {
            Range<Token> range = entry.getKey();
            boolean intersects = intersects(searcherCallback.filterRange(), searcherCallback.isSingleToken(), searcherCallback.isFullRange(), range);
            if (intersects) {
                Indexer indexer = entry.getValue();
                IndexSearcher searcher = indexer.acquire();
                indexSearchers.put(indexer, searcher);
                indexReaders.add(searcher.getIndexReader());
            }
        }
        IndexReader[] indexReadersArr = new IndexReader[indexReaders.size()];
        indexReaders.toArray(indexReadersArr);
        MultiReader multiReader = new MultiReader(indexReadersArr, false);
        IndexSearcher allSearcher = new IndexSearcher(multiReader, executorService);
        try {
            return searcherCallback.doWithSearcher(allSearcher);
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            try {
                multiReader.close();
            } catch (IOException e) {
                logger.error("Could not close reader", e);
            }
            for (Map.Entry<Indexer, IndexSearcher> entry : indexSearchers.entrySet()) {
                entry.getKey().release(entry.getValue());
            }
        }
    }

    private boolean intersects(Range<Token> filterRange, boolean isSingleToken, boolean isFullRange, Range<Token> range) {
        boolean intersects;
        if (isFullRange) intersects = true;
        else if (isSingleToken) intersects = range.contains(filterRange.left);
        else {
            intersects = range.intersects(filterRange);
        }
        return intersects;
    }

    public Indexer indexer(DecoratedKey decoratedKey) {
        for (Map.Entry<Range<Token>, Indexer> entry : indexers.entrySet()) {
            if (entry.getKey().contains(decoratedKey.getToken())) return entry.getValue();
        }
        throw new IllegalStateException("No VNodeIndexer found for indexing key [" + decoratedKey + "]");
    }

    public void commit() {
        for (Indexer indexer : indexers.values()) {
            indexer.commit();
        }
    }

    public long size() {
        long size = 0;
        for (Indexer indexer : indexers.values()) {
            size += (indexer == null) ? 0 : indexer.size();
        }
        return size;
    }

    public long liveSize() {
        long size = 0;
        for (Indexer indexer : indexers.values()) {
            size += (indexer == null) ? 0 : indexer.liveSize();
        }
        return size;
    }

    public void remove() {
        for (Indexer indexer : indexers.values()) {
            if (indexer != null) {
                indexer.removeIndex();
            }
        }
    }

    public void truncate(long l) {
        for (Indexer indexer : indexers.values()) {
            if (indexer != null) {
                indexer.truncate(l);
                logger.warn(indexName + " Truncated index {}.", indexName);
            }
        }
    }

}
