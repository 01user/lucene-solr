package org.apache.solr.store.blob.process;

import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.util.Map;

import javax.servlet.http.HttpServletRequest;

import org.apache.solr.core.CoreContainer;
import org.apache.solr.servlet.SolrRequestParsers;
import org.apache.solr.store.blob.metadata.PushPullData;
import org.apache.solr.store.blob.process.CorePullerFeeder.PullCoreInfo;
import org.apache.solr.store.blob.util.DeduplicatingList;
import com.google.common.annotations.VisibleForTesting;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tracks cores that are being queried and if necessary enqueues them for pull from blob store
 *
 * @author msiddavanahalli
 * @since 214/solr.6
 */
public class CorePullTracker {
    private static final Logger logger = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

    static private final int TRACKING_LIST_MAX_SIZE = 500000;

    private final DeduplicatingList<String, PullCoreInfo> coresToPull;

    /* Config value that enables core pulls */
    @VisibleForTesting
    public static boolean isBackgroundPullEnabled = true; // TODO : make configurable

    private static CorePullTracker INSTANCE = null;

    // Let's define these paths in yet another place in the code...
    private static final String QUERY_PATH_PREFIX = "/select";
    private static final String SPELLCHECK_PATH_PREFIX = "/spellcheck";
    private static final String RESULTPROMOTION_PATH_PREFIX = "/result_promotion";
    private static final String INDEXLOOKUP_PATH_PREFIX = "/indexLookup";
    private static final String HIGHLIGHT_PATH_PREFIX = "/highlight";
    private static final String BACKUP_PATH_PREFIX = "/backup";

    /**
     * Get the CorePullTracker instance to track cores that need to be pulled in from Blob.
     */
    public synchronized static CorePullTracker get() {
        if (INSTANCE == null) {
            INSTANCE = new CorePullTracker();
        }
        return INSTANCE;
    }

    public CorePullTracker() {
        coresToPull = new DeduplicatingList<>(TRACKING_LIST_MAX_SIZE, new CorePullerFeeder.PullCoreInfoMerger());
    }

    /**
     * If the local core is stale, enqueues it to be pulled in from blob Note : If there is no coreName available in the
     * requestPath we simply ignore the request.
     */
    public void enqueueForPullIfNecessary(HttpServletRequest request, PushPullData pushPullData, 
            CoreContainer cores) throws IOException {
    	  String servletPath = request.getServletPath();

      // TODO: do we need isBackgroundPullEnabled in addition to isBlobEnabled? If not we should remove this.
      // TODO: always pull for this hack - want to check if local core is up to date
      if (isBackgroundPullEnabled && pushPullData.getSharedStoreName() != null && shouldPullStale(servletPath)) {
          enqueueForPull(pushPullData, false, false);
      }
    }

    /**
     * Enqueues a core for pull
     * 
     * @param pushPullData pull request data required to interact with blob store
     * @param createCoreIfAbsent whether to create core before pulling if absent
     * @param waitForSearcher whether to wait for newly pulled contents be reflected through searcher 
     */
    public void enqueueForPull(PushPullData pushPullData, boolean createCoreIfAbsent, boolean waitForSearcher) {
        PullCoreInfo pci = new PullCoreInfo(pushPullData, waitForSearcher, createCoreIfAbsent);
        try {
            coresToPull.addDeduplicated(pci, false);
        } catch (InterruptedException ie) {
            logger.warn("Core " + pushPullData.getSharedStoreName() + " not added to Blob pull list. System shutting down?");
            Thread.currentThread().interrupt();
        }
    }

    public PullCoreInfo getCoreToPull() throws InterruptedException {
        return coresToPull.removeFirst();
    }

    /**
     * Get a set of request params for the given request
     */
    private Map<String, String[]> getRequestParams(HttpServletRequest request) {
        Map<String, String[]> params;

        // if this is a POST, calling request#getParameter(String) may cause the input stream (body) to be read, in
        // search of that parameter. Any subsequent calls to read the request body will fail, as the stream is then
        // empty.
        String method = request.getMethod();
        if ("POST".equals(method)) {
            params = SolrRequestParsers.parseQueryString(request.getQueryString()).getMap();
        } else {
            params = request.getParameterMap();
        }

        return params;
    }

    /** @return the *first* value for the specified parameter or {@code defaultValue}, if not present. */
    private String getParameterValue(Map<String, String[]> params, String parameterName, String defaultValue) {
        String[] values = params.get(parameterName);
        if (values != null && values.length == 1) {
            return values[0];
        } else {
            return defaultValue;
        }
    }

    /**
     * Determine if our request should trigger a pull from Blob when local core is stale.
     */
    private boolean shouldPullStale(String servletPath) {
        // get the request handler from the path (taken from SolrDispatchFilter)
        int idx = servletPath.indexOf('/', 1);
        if (idx > 1) {
            String action = servletPath.substring(idx);
            // Note: if new paths are added for new types of access to the searchserver, the set of paths that trigger
            // a pull of a stale core below might have to be updated.
            return action.startsWith(QUERY_PATH_PREFIX)
                    || action.startsWith(SPELLCHECK_PATH_PREFIX)
                    // TODO || action.startsWith(SynonymDataHandler.SYNONYM_DATA_HANDLER_PATH)
                    || action.startsWith(RESULTPROMOTION_PATH_PREFIX)
                    || action.startsWith(INDEXLOOKUP_PATH_PREFIX)
                    || action.startsWith(HIGHLIGHT_PATH_PREFIX)
                    || action.startsWith(BACKUP_PATH_PREFIX);
        } else {
            return false;
        }
    }
}
