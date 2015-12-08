/*
 *    Copyright (C) 2015 QAware GmbH
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */
package de.qaware.chronix.solr.client.stream;

import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import de.qaware.chronix.converter.TimeSeriesConverter;
import de.qaware.chronix.solr.client.ChronixSolrStorageConstants;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.common.SolrDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.concurrent.Executors;

/**
 * The solr streaming service let one stream data from Solr.
 *
 * @param <T> type of the returned class
 * @author f.lautenschlager
 */
public class SolrStreamingService<T> implements Iterator<T> {

    /**
     * The class logger
     */
    private static final Logger LOGGER = LoggerFactory.getLogger(SolrStreamingService.class);

    /**
     * The query and connection to solr
     */
    private final SolrQuery query;
    private final SolrClient connection;

    /**
     * Converter for converting the documents
     */
    private final TimeSeriesConverter<T> converter;

    /**
     * Query parameters
     */
    private int nrOfTimeSeriesPerBatch;
    private long nrOfAvailableTimeSeries = -1;
    private int currentDocumentCount = 0;


    /**
     * The executor service to do the work asynchronously
     */
    private final ListeningExecutorService service = MoreExecutors.listeningDecorator(Executors.newCachedThreadPool());

    /**
     * The handler for this service
     */
    private final SolrStreamingHandler solrStreamingHandler;


    /**
     * Handler waiting for future callbacks
     */
    private TimeSeriesHandler<T> timeSeriesHandler;

    /**
     * Start and end of the query to filter points on client side
     */
    private long queryStart;
    private long queryEnd;

    /**
     * Constructs a streaming service
     *
     * @param converter              - the converter to convert documents
     * @param query                  - the solr query
     * @param connection             - the solr server connection
     * @param nrOfTimeSeriesPerBatch - the number of time series that are read by one query
     */
    public SolrStreamingService(TimeSeriesConverter<T> converter, SolrQuery query, SolrClient connection, int nrOfTimeSeriesPerBatch) {
        this.converter = converter;
        this.solrStreamingHandler = new SolrStreamingHandler();
        this.query = query;
        this.connection = connection;
        this.nrOfTimeSeriesPerBatch = nrOfTimeSeriesPerBatch;
    }

    @Override
    public boolean hasNext() {
        if (nrOfAvailableTimeSeries == -1) {
            //Do only once for each query
            initStreamingService(query, connection);
        }

        if (nrOfAvailableTimeSeries <= 0) {
            return false;
        }
        //initialize the callback handler.
        //The queue size have to be the size of time series per batch
        if (timeSeriesHandler == null) {
            timeSeriesHandler = new TimeSeriesHandler<>(nrOfTimeSeriesPerBatch);
        }

        return currentDocumentCount < nrOfAvailableTimeSeries;
    }


    private void initStreamingService(SolrQuery query, SolrClient connection) {
        SolrQuery solrQuery = query.getCopy();
        solrQuery.setRows(0);
        //we do not need any data from the server expect the total number of found documents
        solrQuery.setFields("");

        try {
            QueryResponse response = connection.query(solrQuery);
            nrOfAvailableTimeSeries = response.getResults().getNumFound();
            queryStart = (long) response.getResponseHeader().get(ChronixSolrStorageConstants.QUERY_START_LONG);
            queryEnd = (long) response.getResponseHeader().get(ChronixSolrStorageConstants.QUERY_END_LONG);

        } catch (SolrServerException | IOException e) {
            LOGGER.error("SolrServerException occurred while querying server.", e);
        }
    }

    @Override
    public T next() {
        if (currentDocumentCount % nrOfTimeSeriesPerBatch == 0) {
            streamDocumentsFromSolr();
        }

        currentDocumentCount += 1;
        if (currentDocumentCount == nrOfAvailableTimeSeries) {
            LOGGER.debug("Shutting down the thread pool while all points are converted.");
            service.shutdown();
        }

        if (currentDocumentCount > nrOfAvailableTimeSeries) {
            throw new NoSuchElementException("Index " + currentDocumentCount + " greater than available time series " + nrOfAvailableTimeSeries);
        }

        return timeSeriesHandler.take();
    }

    private void streamDocumentsFromSolr() {
        SolrQuery solrQuery = query.getCopy();
        solrQuery.setRows(nrOfTimeSeriesPerBatch);
        solrQuery.setStart(currentDocumentCount);

        solrStreamingHandler.init(nrOfTimeSeriesPerBatch, currentDocumentCount);

        try {
            connection.queryAndStreamResponse(solrQuery, solrStreamingHandler);
            SolrDocument document;
            do {
                document = solrStreamingHandler.pool();
                if (document != null) {
                    ListenableFuture future = service.submit(new TimeSeriesConverterCaller<>(document, converter, queryStart, queryEnd));
                    Futures.addCallback(future, timeSeriesHandler);
                }

            } while (solrStreamingHandler.canPoll());
        } catch (SolrServerException | IOException e) {
            LOGGER.warn("Exception while streaming the data points from Solr", e);
        }
    }

}
