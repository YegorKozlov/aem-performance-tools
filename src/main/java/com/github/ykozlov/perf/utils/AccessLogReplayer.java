package com.github.ykozlov.perf.utils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.List;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * @author Yegor Kozlov
 */
public class AccessLogReplayer {
    private static final Logger logger = LoggerFactory.getLogger(AccessLogReplayer.class);

    static void usage() {
        System.err.println("Usage: com.github.ykozlov.perf.utils.AccessLogReplayer [options...] <file>");
        System.err.println("  --threads N                    Number of multiple requests to make at a time");
        System.err.println("  --base-url  url                Target url, e.g. https://we-retail.com");
        System.err.println("  --warmup                       Warmup http client before execution");
        System.err.println("  --random                       Randomly select lines from the input file");
        System.err.println("  --minutes                      Minutes to max. to spend on benchmarking");
        System.err.println("  --top N                        Process top N entries from the input file");
        System.err.println("  --dump                         Dump html responses in ./yyyy-mm-dd hh:mm directory");
    }

    public static void main(String[] args) throws Exception {
        String baseUrl = "http://admin:admin@localhost:4502";
        String inputFile = null;
        int numThreads = 1;
        boolean random = false;
        int minutes = 0;
        boolean dump = false;
        boolean warmup = false;
        int top = 0;
        String saveAs = "access-replay.xlsx";
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--threads":
                    numThreads = Integer.parseInt(args[++i]);
                    break;
                case "--base-url":
                    baseUrl = args[++i];
                    break;
                case "--random":
                    random = true;
                    break;
                case "--warmup":
                    warmup = true;
                    break;
                case "--minutes":
                    minutes = Integer.parseInt(args[++i]);
                    break;
                case "--top":
                    top = Integer.parseInt(args[++i]);
                    break;
                case "--dump":
                    dump = true;
                    break;
                case "--saveAs":
                    saveAs = args[++i];
                    break;
                default:
                    if (args[i].startsWith("-"))
                        throw new IllegalArgumentException("unknown input argument: " + args[i]);

                    inputFile = args[i];
                    break;
            }
        }

        if (inputFile == null) {
            usage();
            return;
        }


        File cwd = new File(".");
        long started = System.currentTimeMillis();
        AEMAgent.Builder builder = new AEMAgent.Builder()
                .withBaseUrl(baseUrl)
                .withThreadCount(numThreads);
        if (dump) {
            File reportDir = new File(cwd, new SimpleDateFormat("yyyy-MM-dd.HH.mm").format(System.currentTimeMillis()));
            reportDir.mkdirs();

            builder.dumpTo(reportDir);
        }

        AEMAgent agent = builder.build();

        Pattern ptrn = Pattern.compile("(.+) \"(.+)\" (.+) \\[(.+)\\] \"([A-Z]+) (.+) HTTP/1.1\" (\\d+) (.+) \"(.*?)\" \"(.*?)\"");

        List<String> lines = Files.readAllLines(Paths.get(inputFile), Charset.defaultCharset());
        int cnt = 0;
        Random rnd = new Random();
        for (int i = 1; i < lines.size(); i++) {

            int idx = random ? rnd.nextInt(lines.size()) : i;
            String ln = lines.get(idx);
            Matcher m = ptrn.matcher(ln);
            if(!m.matches()){
                logger.warn("invalid common log entry: {}", ln);
                continue;
            }

            String method = m.group(5);
            String path = m.group(6);
            int responseCode = Integer.parseInt(m.group(7));
            if("GET".equals(method) && responseCode == 200) {
                agent.ajaxGet(path);
            }

            if (top > 0 && ++cnt == top) break;
        }
        agent.shutdown(minutes * 60);
        agent.getReport().save(new File(cwd, saveAs).getPath());
        long finished = System.currentTimeMillis();
        int numJobs = agent.getNumProcessed();
        logger.info("{} jobs done in {} seconds, {} jobs/second, average: {} ms, {} KB downloaded",
                numJobs, (finished - started) / 1000., String.format("%.2f", numJobs * 1000. / (finished - started)),
                agent.getAverageTime(), agent.getBytesSent() / 1024
        );

        int topN = Math.min(10, numJobs);
        System.err.println(topN + " longest requests, ms:");
        List<Long> times = agent.getTop(topN);
        for (int i = 0; i < times.size(); i++) {
            System.err.println(times.get(times.size() - 1 - i));
        }

    }

}
