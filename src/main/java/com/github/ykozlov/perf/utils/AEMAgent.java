package com.github.ykozlov.perf.utils;

import org.apache.commons.lang3.time.DurationFormatUtils;
import org.apache.http.Header;
import org.apache.http.HttpEntity;
import org.apache.http.NameValuePair;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.*;
import org.apache.http.conn.ssl.NoopHostnameVerifier;
import org.apache.http.conn.ssl.SSLConnectionSocketFactory;
import org.apache.http.conn.ssl.TrustSelfSignedStrategy;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.ssl.SSLContextBuilder;
import org.apache.http.util.EntityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLContext;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * @author Yegor Kozlov
 */
public class AEMAgent {
    private static final Logger logger = LoggerFactory.getLogger(AEMAgent.class);

    public static String[] DEFAULT_COLUMNS = {
            "Timestamp", "Path", "Method", "Status", "Time To First Byte", "Total Time", "Content-Length"};

    public interface ColumnData {
        void set(Report.Row row, int columnIndex, String response);
    }

    private CloseableHttpClient client;
    private String host;
    private ExecutorService executor;
    private final Report report;
    private List<Long> times;
    private Map<Pattern, String> rewritePatterns;
    long timeStarted;
    int numResources;
    long bytesSent;
    int numErrors;
    AtomicInteger counter = new AtomicInteger();
    CredentialsProvider credentialsProvider;
    int nThreads;
    HttpClientBuilder builder;
    File dumpDir;
    Map<Integer, ColumnData> addedCols = new LinkedHashMap<>();
    String requestIdHeader;
    String userAgent = "Fleetcor AEM Agent";

    public AEMAgent(String host) {
        this(host, 1, null);
    }

    public AEMAgent(String host, int threads) {
        this(host, threads, null);
    }

    public AEMAgent(String host, int nThreads, CredentialsProvider credentialsProvider) {
        this.timeStarted = System.currentTimeMillis();
        this.host = host;
        this.nThreads = nThreads;
        this.executor = new RequestExecutor(nThreads);
        this.credentialsProvider = credentialsProvider;
        counter = new AtomicInteger();
        times = Collections.synchronizedList(new ArrayList<Long>());

        report = new Report();
        report.setColumns(DEFAULT_COLUMNS);
        report.setColumnWidth(0, 20 * 254);
        report.setColumnWidth(1, 70 * 254);

    }

    public HttpClientBuilder httpClientBuilder() {
        if (builder == null) {

            builder =
                    HttpClients.custom()
                            .disableRedirectHandling()
                            .disableAutomaticRetries()
                            .setUserAgent(userAgent)
            ;

            try {
                SSLContextBuilder sslContextBuilder = new SSLContextBuilder();
                sslContextBuilder.loadTrustMaterial(KeyStore.getInstance(KeyStore.getDefaultType()),
                        new TrustSelfSignedStrategy() {
                            @Override
                            public boolean isTrusted(X509Certificate[] chain,
                                                     String authType)
                                    throws CertificateException {
                                return true;
                            }
                        });

                SSLContext sslContext = sslContextBuilder.build();
                // set a hostname verifier that verifies all
                SSLConnectionSocketFactory socketFactory = new SSLConnectionSocketFactory(
                        sslContext,
                        NoopHostnameVerifier.INSTANCE);
                builder.setSSLContext(sslContext);
                builder.setSSLSocketFactory(socketFactory);
            } catch (GeneralSecurityException e) {
                throw new RuntimeException(e);
            }

            builder.setMaxConnTotal(nThreads).setMaxConnPerRoute(nThreads);
            if (credentialsProvider != null) builder.setDefaultCredentialsProvider(credentialsProvider);
        }
        return builder;

    }

    public AEMAgent withRequestId(String headerName) {
        requestIdHeader = headerName;
        return this;
    }

    public void addColumn(String col, ColumnData data) {
        int columnIndex = Arrays.asList(report.getColumns()).indexOf(col);
        if (columnIndex < 0) {
            columnIndex = report.addColumn(col);
        }
        addedCols.put(columnIndex, data);
    }

    private String requestUrl(String url) {
        return url.startsWith("http://") || url.startsWith("https://") ? url : (host + url);
    }

    public void setRewritePatterns(Map<Pattern, String> rewritePatterns) {
        this.rewritePatterns = rewritePatterns;
    }

    public void setUserAgent(String userAgent) {
        this.userAgent = userAgent;
    }

    public String post(String url, List<NameValuePair> data) throws Exception {
        return post(url, new UrlEncodedFormEntity(data), null);
    }

    public String post(String url, HttpEntity data, BiFunction<String, Report.Row, Void> callback) throws Exception {
        HttpPost method = new HttpPost(requestUrl(url));
        method.setEntity(data);
        return process(method, callback);
    }

    public void warmup(String url) throws Exception {
        HttpGet method = new HttpGet(requestUrl(url));
        try (CloseableHttpResponse response = getHttpClient().execute(method)) {

            HttpEntity e = response.getEntity();
            EntityUtils.consumeQuietly(e);
        }
    }

    public String get(String url) throws Exception {
        return process(new HttpGet(requestUrl(url)));
    }

    public String get(String url, Map<String, String> headers) throws Exception {
        HttpGet method = new HttpGet(requestUrl(url));
        if(headers != null) for(String key : headers.keySet()){
            method.setHeader(key, headers.get(key));
        }
        return process(method);
    }

    public String options(String urlath) throws Exception {
        return process(new HttpOptions(requestUrl(urlath)));
    }

    public synchronized CloseableHttpClient getHttpClient() {
        if (client == null) {
            client = httpClientBuilder().build();
        }
        return client;
    }

    String process(HttpRequestBase method) throws IOException {
        return process(method, null);
    }

    String process(HttpRequestBase method, BiFunction<String, Report.Row, Void> callback) throws IOException {
        String url = method.getURI().toString();
        Report.Row row;
        synchronized (report) {
            row = report.createRow();
        }
        if(requestIdHeader != null) {
            String rId = UUID.randomUUID().toString();
            method.setHeader(requestIdHeader, rId);
        }
        row.setValue(0, new Date());
        row.setCellStyle(0, Report.STYLE_DATETIME);
        row.setValue(1, url.length() > 255 ? url : ("=HYPERLINK(\"" + url + "\")"));
        row.setCellStyle(1, Report.STYLE_HYPERLINK);
        row.setValue(2, method.getMethod());

        long t0 = System.currentTimeMillis();
        try (CloseableHttpResponse response = getHttpClient().execute(method)) {

            HttpEntity e = response.getEntity();
            long delta = System.currentTimeMillis() - t0;
            String txt = EntityUtils.toString(e);
            bytesSent += txt.length();

            int statusCode = response.getStatusLine().getStatusCode();
            row.setValue(3, String.valueOf(statusCode));
            times.add(delta);
            row.setValue(4, String.valueOf(delta));
            row.setValue(5, String.valueOf(System.currentTimeMillis() - t0));
            row.setValue(6, String.valueOf(txt.length()));

            for(Map.Entry<Integer, ColumnData> col : addedCols.entrySet()){
                int colIdx = col.getKey();
                ColumnData data = col.getValue();
                data.set(row, colIdx, txt);
            }

            if(callback != null) callback.apply(txt, row);

            if (dumpDir != null) {
                File file = new File(dumpDir, url.replaceAll("https?://", "").replace("?", "/").replaceAll("[:\"<>]", "_"));
                file.getParentFile().mkdirs();
                try (FileWriter fw = new FileWriter(file)) {
                    fw.write(txt);
                }
            }

            logger.debug("{}\t{}\t{}\t{}",statusCode, delta,txt.length(),url);
            if (statusCode < 200 || statusCode >=300 ) {
                row.setRowStyle(Report.STYLE_BAD);
                logger.error("statusCode: {}, uri: {}, reason: {}",
                        statusCode, method.getURI().toString(), response.getStatusLine().getReasonPhrase());

                if (logger.isDebugEnabled()) {
                    for (Header h : response.getAllHeaders()) {
                        logger.debug(h.toString());
                    }
                } else if (statusCode == 302 || statusCode == 301) {
                    logger.error("  Location: {}", response.getFirstHeader("Location").getValue());
                }
                numErrors++;
            }

            return txt;
        } catch (Throwable e) {
            logger.error("request failed", e);
            row.setRowStyle(Report.STYLE_BAD);
            row.setValue(6, e.getMessage());
            throw new IOException(e);
        }
    }


    public String head(String url) throws Exception {
        return process(new HttpHead(requestUrl(url)));
    }

    public Future<String> ajaxPost(String url, String data, ContentType contentType, BiFunction<String, Report.Row, Void> callback) {
        numResources++;
        return executor.submit(() -> post(url, new StringEntity(data, contentType), callback));
    }

    public Future<String> ajaxPost(String url, List<NameValuePair> data) throws Exception {
        numResources++;
        return executor.submit(() -> post(url, data));
    }

    public Future<String> ajaxGet(String url) {
        numResources++;
        return executor.submit(() -> get(url));
    }

    public Future<String> ajaxGet(String url, Map<String, String> headers) {
        numResources++;
        return executor.submit(() -> get(url, headers));
    }

    public Future<String> ajaxOptions(final String url) {
        numResources++;
        return executor.submit(() -> options(url));
    }

    public void shutdown() throws InterruptedException, IOException {
        shutdown(0);
    }

    public void shutdown(int seconds) throws InterruptedException, IOException {
        executor.shutdown();
        int elapsed = 0;
        long interval = 1;
        while (!executor.awaitTermination(interval, TimeUnit.SECONDS)) {
            logger.debug("Awaiting completion of threads ");
            elapsed += interval;

            if (seconds > 0 && elapsed >= seconds) {
                logger.info("stopping actively executing tasks");
                List<Runnable> tasks = executor.shutdownNow();
                logger.info("{} tasks cancelled", tasks.size());
                break;
            }
        }
        getHttpClient().close();
    }

    public Report getReport() throws InterruptedException {
        return report;
    }

    public List<Long> getTop(int N) {
        Collections.sort(times);
        return times.subList(Math.max(0, times.size() - N), times.size());
    }

    public int getNumProcessed() {
        return numResources;
    }

    public int getNumErrors() {
        return numErrors;
    }

    public long getBytesSent() {
        return bytesSent;
    }

    public long getAverageTime() {
        long avg = 0;
        for (long ms : times) avg += ms;
        return times.size() == 0 ? 0 : avg / times.size();
    }


    public String urlRewrite(String url) {
        for (Pattern ptrn : rewritePatterns.keySet()) {
            Matcher m = ptrn.matcher(url);
            String replacement = rewritePatterns.get(ptrn);
            String s = m.replaceAll(replacement);
            if (!s.equals(url)) {
                return s;
            }
        }
        return url;
    }

    public void setDumpDir(File dir) {
        dumpDir = dir;
    }

    public static class RequestExecutor extends ScheduledThreadPoolExecutor {

        long timeStarted;

        public RequestExecutor(int nThreads) {
            super(nThreads);
            timeStarted = System.currentTimeMillis();
        }

        @Override
        protected void afterExecute(Runnable r, Throwable t) {
            super.afterExecute(r, t);
            long i = getCompletedTaskCount() + 1;

            long numResources = getTaskCount();

            long remainingCycles = numResources - i;
            long estimatedTime = remainingCycles * (System.currentTimeMillis() - timeStarted) / i;
            String eta = DurationFormatUtils.formatDurationWords(estimatedTime, true, true);
            String pct = String.format("%.1f", i * 100. / numResources);
            logger.info("{} resources processed of {} ({}%), eta: {}",
                    i, numResources, pct, eta);

            if (t != null) {
                logger.error("job {} of {} failed", i, numResources, t);
            }

        }
    }

    public static class Builder {
        private String url;
        private int threadCount;
        private CredentialsProvider credentials;
        private Map<Pattern, String> rewritePatterns;
        private File dir;
        private String userAgent;

        public Builder() {
            rewritePatterns = new LinkedHashMap<>();
        }

        public Builder withBaseUrl(String url) {
            this.url = url;
            return this;
        }

        public final Builder withUserAgent(String userAgent) {
            this.userAgent = userAgent;
            return this;
        }

        public Builder withThreadCount(int threadCount) {
            if (threadCount <= 0) {
                throw new IllegalArgumentException("threadCount must be a positive integer.");
            }

            this.threadCount = threadCount;
            return this;
        }

        public Builder withCredentials(CredentialsProvider credentials) {
            this.credentials = credentials;
            return this;
        }

        public Builder withRewritePattern(Pattern ptrn, String target) {
            rewritePatterns.put(ptrn, target);
            return this;
        }

        public Builder dumpTo(File dir) {
            this.dir = dir;
            dir.mkdirs();
            return this;
        }

        public AEMAgent build() {
            AEMAgent agent = new AEMAgent(url, threadCount, credentials);

            if (!rewritePatterns.isEmpty()) {
                agent.setRewritePatterns(rewritePatterns);
            }
            if (dir != null) agent.setDumpDir(dir);
            if (userAgent != null) agent.setUserAgent(userAgent);
            return agent;
        }
    }
}
