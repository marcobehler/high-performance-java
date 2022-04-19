package com.mycompany.app;

import com.mycompany.app.servlets.DatabaseServlet;
import com.mycompany.app.servlets.PlainJavaServlet;
import org.HdrHistogram.AbstractHistogram;
import org.HdrHistogram.Histogram;
import org.HdrHistogram.HistogramLogReader;
import org.apache.commons.io.IOUtils;
import org.eclipse.jetty.perf.histogram.loader.ResponseStatusListener;
import org.eclipse.jetty.perf.histogram.loader.ResponseTimeListener;
import org.eclipse.jetty.perf.histogram.server.LatencyRecordingChannelListener;
import org.eclipse.jetty.perf.monitoring.ConfigurableMonitor;
import org.eclipse.jetty.perf.util.ReportUtil;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.DefaultHandler;
import org.eclipse.jetty.server.handler.HandlerList;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.mortbay.jetty.load.generator.LoadGenerator;
import org.mortbay.jetty.load.generator.Resource;
import org.mortbay.jetty.orchestrator.Cluster;
import org.mortbay.jetty.orchestrator.ClusterTools;
import org.mortbay.jetty.orchestrator.NodeArray;
import org.mortbay.jetty.orchestrator.NodeArrayFuture;
import org.mortbay.jetty.orchestrator.configuration.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Hello world!
 */
public class PerformanceTest {


    /**
     * RPS
     */

    private static final int RPS_LOADER = 250;
    public static final int RPS_PROBE = 2;


    /**
     * --------- IPS --------------
     */

    public static final String SERVER_IP = "3.67.204.198";

    public static final String PROBE_IP = "3.123.0.194";


    public static List<String> LOADER_IPS = List.of("3.70.172.123", "3.69.46.52", "18.157.184.15", "35.158.101.57");


    /**
     * --------- PARAMS --------------
     */
    private static Mode mode = Mode.DEPLOY_JAVA;

    /** e.g. play with
     * 1000  ( 250 x 4)
     * 5000 ( 1250 x 4)
     * 20000 (5000 x 4)
     * 30000 (7500 x 4)
     * 40000 (10000 x 4)
     */


    private static final Duration WARMUP_DURATION = Duration.ofSeconds(10);
    private static final Duration RUN_DURATION = Duration.ofSeconds(30);

    public static final String BASE_FOLDER = ".\\target\\results";

    public static final Resource REQUEST_URI = new Resource("/");
    public static final int PORT = 8080;


    public enum Mode {
        DEPLOY_JAVA, DEPLOY_DB, MANUAL
    }

    private static Logger LOGGER = LoggerFactory.getLogger(PerformanceTest.class);

    
    private static final EnumSet<ConfigurableMonitor.Item> MONITORED_ITEMS = EnumSet.of(
            ConfigurableMonitor.Item.CMDLINE_CPU,
            ConfigurableMonitor.Item.CMDLINE_MEMORY,
            ConfigurableMonitor.Item.CMDLINE_NETWORK
    );

    private static final EnumSet<ConfigurableMonitor.Item> SERVER_ITEMS = copy(MONITORED_ITEMS,
            ConfigurableMonitor.Item.ASYNC_PROF_CPU,
            ConfigurableMonitor.Item.JHICCUP);


    public static void main(String[] args) throws IOException {
        // set SSH username here
        System.setProperty("user.name", "ubuntu");

        Files.createDirectories(Paths.get(BASE_FOLDER));

        SimpleNodeArrayConfiguration probeConfig = probeConfig();
        SimpleNodeArrayConfiguration loadGeneratorConfig = loaderConfig();
        SimpleNodeArrayConfiguration serverConfig = serverConfig();

        SimpleClusterConfiguration cfg = new SimpleClusterConfiguration()
                .jvm(new Jvm((fs, h) -> "/usr/bin/java", "-Xmx3g"))
                .nodeArray(probeConfig)
                .nodeArray(serverConfig)
                .nodeArray(loadGeneratorConfig);



        int participantCount = cfg.nodeArrays().stream().mapToInt(na -> na.nodes().size()).sum() + 1; // + 1 b/c of the test its.

        try (Cluster cluster = new Cluster(cfg)) {
            NodeArray serverArray = cluster.nodeArray("server");
            NodeArray loadersArray = cluster.nodeArray("load-generator");
            NodeArray probeArray = cluster.nodeArray("probe");

            serverArray.executeOnAll(tools -> startServer(tools.nodeEnvironment())).get(30, TimeUnit.SECONDS);
            loadersArray.executeOnAll(tools -> runLoadGenerator(tools.nodeEnvironment())).get(30, TimeUnit.SECONDS);
            probeArray.executeOnAll(tools -> runProbe(tools.nodeEnvironment())).get(30, TimeUnit.SECONDS);

            LOGGER.info("Warming up...");
            Thread.sleep(WARMUP_DURATION.toMillis());

            LOGGER.info("Running...");
            //long before = System.nanoTime();

            NodeArrayFuture serverFuture = serverArray.executeOnAll(tools -> recordServerMetrics(participantCount, tools));
            NodeArrayFuture loadersFuture = loadersArray.executeOnAll(tools -> recordClientMetrics(participantCount, tools));
            NodeArrayFuture probeFuture = probeArray.executeOnAll(tools -> recordClientMetrics(participantCount, tools));


            try {
                // signal all participants to start
                cluster.tools().barrier("run-start-barrier", participantCount).await(30, TimeUnit.SECONDS);
                // wait for the run duration
                Thread.sleep(RUN_DURATION.toMillis());
                // signal all participants to stop
                cluster.tools().barrier("run-end-barrier", participantCount).await(30, TimeUnit.SECONDS);
            } finally {
                // wait for all report files to be written;
                // do it in a finally so that if the above barrier awaits time out b/c a job threw an exception
                // the future.get() call will re-throw the exception and it'll be logged.
                try {
                    serverFuture.get(30, TimeUnit.SECONDS);
                    probeFuture.get(30, TimeUnit.SECONDS);
                    loadersFuture.get(30, TimeUnit.SECONDS);
                } catch (Exception e){
                    e.printStackTrace();
                }
            }

            // stop server
            serverArray.executeOnAll((tools) -> stopServer(tools.nodeEnvironment())).get(60, TimeUnit.SECONDS);

            downloadReports(cluster, probeConfig, loadGeneratorConfig);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static SimpleNodeArrayConfiguration loaderConfig() {
        SimpleNodeArrayConfiguration loadGeneratorConfig = new SimpleNodeArrayConfiguration("load-generator");

        LOADER_IPS.forEach(ip -> {
            loadGeneratorConfig.node(new Node("loader-" + toDirectoryName(ip), ip));
        });
        return loadGeneratorConfig;
    }

    private static SimpleNodeArrayConfiguration probeConfig() {
        return new SimpleNodeArrayConfiguration("probe")
                .node(new Node("probe-" + toDirectoryName(PROBE_IP), PROBE_IP));
    }

    private static String toDirectoryName(String ip) {
        return ip.replaceAll("\\.", "-");
    }

    private static SimpleNodeArrayConfiguration serverConfig() {
        return new SimpleNodeArrayConfiguration("server")
                .node(new Node("server-" + toDirectoryName(SERVER_IP), SERVER_IP));
    }

    private static void downloadReports(Cluster cluster, SimpleNodeArrayConfiguration probeConfig, SimpleNodeArrayConfiguration loadGeneratorConfig) throws IOException {
        String dateTimeString = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
        Path targetPath = Paths.get(BASE_FOLDER).resolve(dateTimeString);

        List<NodeArray> nodeArrays = List.of(cluster.nodeArray("server"),
                cluster.nodeArray("load-generator"),
                cluster.nodeArray("probe"));
        for (NodeArray nodeArray : nodeArrays) {

            ReportUtil.download(nodeArray, targetPath);
            ReportUtil.transformPerfHisto(nodeArray, targetPath);
            ReportUtil.transformJHiccupHisto(nodeArray, targetPath);
            transformAndAddToPlot(dateTimeString, nodeArray, targetPath);
        }

        long totalLoadersRequestCount = RPS_LOADER * LOADER_IPS.size() * RUN_DURATION.toSeconds();
        assertThroughput(targetPath, loadGeneratorConfig, totalLoadersRequestCount, 1);

        long totalProbeRequestCount = RPS_PROBE * 1 * RUN_DURATION.toSeconds();
        assertThroughput(targetPath, probeConfig, totalProbeRequestCount, 1);
    }

    private static void transformAndAddToPlot(String dateTimeString, NodeArray nodeArray, Path targetPath) {
        nodeArray.ids().stream().filter(id -> id.startsWith("probe")).forEach(id -> {
            Path plotFolder = Paths.get(BASE_FOLDER).resolve("plot");
            if (!Files.exists(plotFolder)) {
                try {
                    Files.createDirectories(plotFolder);
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
            try {
                Files.copy(targetPath.resolve(id).resolve("perf.hlog.hgrm"), plotFolder.resolve(dateTimeString + "_" + LOADER_IPS.size() + "x" + RPS_LOADER + ".hlog.hgrm"));
                updatePlot(plotFolder);
            } catch (IOException e) {
                System.err.println(e.toString());
                e.printStackTrace();
            }
        });
    }

    private static void recordServerMetrics(int participantCount, ClusterTools tools) throws Exception {
        EnumSet<ConfigurableMonitor.Item> items = isDeploy() ? SERVER_ITEMS : MONITORED_ITEMS;

        try (ConfigurableMonitor ignore = new ConfigurableMonitor(items)) {
            LatencyRecordingChannelListener listener = null;
            if (isDeploy()) {
                listener = (LatencyRecordingChannelListener) tools.nodeEnvironment().get(LatencyRecordingChannelListener.class.getName());
                listener.startRecording();
            }
            tools.barrier("run-start-barrier", participantCount).await();
            tools.barrier("run-end-barrier", participantCount).await();
            if (isDeploy()) {
                if (listener != null) {
                    listener.stopRecording();
                }
            }
        }
    }

    private static void recordClientMetrics(int participantCount, ClusterTools tools) throws Exception {
        try (ConfigurableMonitor ignore = new ConfigurableMonitor(MONITORED_ITEMS)) {
            ResponseTimeListener responseTimeListener = (ResponseTimeListener) tools.nodeEnvironment().get(ResponseTimeListener.class.getName());
            responseTimeListener.startRecording();
            ResponseStatusListener responseStatusListener = (ResponseStatusListener) tools.nodeEnvironment().get(ResponseStatusListener.class.getName());
            responseStatusListener.startRecording();
            tools.barrier("run-start-barrier", participantCount).await();
            tools.barrier("run-end-barrier", participantCount).await();
            responseTimeListener.stopRecording();
            responseStatusListener.stopRecording();
            CompletableFuture<?> cf = (CompletableFuture<?>) tools.nodeEnvironment().get(CompletableFuture.class.getName());
            cf.get();
        }
    }

    private static boolean isDeploy() {
        return mode == Mode.DEPLOY_DB | mode == Mode.DEPLOY_JAVA;
    }



    private static void startServer(ConcurrentMap<String, Object> env) throws Exception {
        if (mode.equals(Mode.MANUAL)) {
            return;
        }

        Server server1 = new Server();

        ServerConnector connector = new ServerConnector(server1);
        connector.setPort(PORT);

        LatencyRecordingChannelListener listener = new LatencyRecordingChannelListener();
        connector.addBean(listener);

        server1.setConnectors(new Connector[]{connector});

        ServletContextHandler context = new ServletContextHandler();
        context.setContextPath("/");

        if (mode == Mode.DEPLOY_JAVA) {
            ServletHolder servletHolder = context.addServlet(PlainJavaServlet.class, "/");
            servletHolder.setInitOrder(0);
        } else if (mode == Mode.DEPLOY_DB) {
            ServletHolder servletHolder = context.addServlet(DatabaseServlet.class, "/");
            servletHolder.setInitOrder(0);
        } else {

            throw new IllegalStateException("not yet implemented");
        }


        HandlerList mainHandlers = new HandlerList();
        mainHandlers.addHandler(context);
        mainHandlers.addHandler(new DefaultHandler());

        server1.setHandler(mainHandlers);

        server1.start();
        env.put(Server.class.getName(), server1);
        env.put(LatencyRecordingChannelListener.class.getName(), listener);
    }

    private static void stopServer(Map<String, Object> env) throws Exception {
        if (mode.equals(Mode.MANUAL)) {
            return;
        }
        ((Server) env.get(Server.class.getName())).stop();
    }

    public static void updatePlot(Path outputFolder) throws IOException {
        Path plot = outputFolder.resolve("plot.html");
        if (Files.exists(plot)) {
            Files.delete(plot);
        }
        Path target = Files.createFile(plot);


        String extension = ".hlog.hgrm";
        String histosPart = Files.list(outputFolder).filter(f -> f.getFileName().toString().endsWith(extension))
                .map(p -> {
                    try {
                        return Files.readString(p);
                    } catch (IOException e) {
                        throw new UnsupportedOperationException();
                    }
                })
                .collect(Collectors.joining("`,`"));


        String namesPart = Files.list(outputFolder).filter(f -> f.getFileName().toString().endsWith(extension))
                .map(p -> {

                    final String fileName = p.getFileName().toString();
                    final String machinesXRequests = fileName.substring(16, fileName.length() - extension.length());
                    final String[] xes = machinesXRequests.split("x");

                    String finalDescriptions = (Integer.parseInt(xes[0]) * Integer.parseInt(xes[1])) + " RPS";
                    return finalDescriptions;

                })
                .collect(Collectors.joining("\",\""));

        String js = "var histos = [`" + histosPart + "`];\n" +
                "    var names  = [\"" + namesPart + "\"];\n";

        try (InputStream resourceAsStream = PerformanceTest.class.getResourceAsStream("/plot_template.html")) {
            String s = IOUtils.toString(resourceAsStream);
            s = s.replace(" // <<< TODO_CHART_DATA >>>", js);
            Files.writeString(target, s);

        } catch (IOException e) {
            e.printStackTrace();
        }

    }


    private static void runProbe(Map<String, Object> env) throws IOException {
        ResponseTimeListener responseTimeListener = new ResponseTimeListener();
        env.put(ResponseTimeListener.class.getName(), responseTimeListener);
        ResponseStatusListener responseStatusListener = new ResponseStatusListener();
        env.put(ResponseStatusListener.class.getName(), responseStatusListener);

        LoadGenerator generator = LoadGenerator.builder()
                .scheme("http")
                .host(SERVER_IP)
                .port(PORT)
                .runFor(WARMUP_DURATION.plus(RUN_DURATION).toSeconds(), TimeUnit.SECONDS)
                .threads(1)
                .rateRampUpPeriod(WARMUP_DURATION.toSeconds() / 2)
                .resourceRate(RPS_PROBE)
                .resource(REQUEST_URI)
                .resourceListener(responseTimeListener)
                .listener(responseTimeListener)
                .resourceListener(responseStatusListener)
                .listener(responseStatusListener)
                .build();

        CompletableFuture<Void> cf = generator.begin();
        env.put(CompletableFuture.class.getName(), cf);

        cf = cf.whenComplete((x, f) -> {
            if (f == null) {
                LOGGER.info("probe generation complete");
            } else {
                LOGGER.info("probe generation failure " + f);
            }
        });

        env.put(CompletableFuture.class.getName(), cf);
        env.put(LoadGenerator.Config.class.getName(), generator.getConfig());
    }

        /*// Start the load generation.
    }*/

    private static void runLoadGenerator(Map<String, Object> env) throws IOException {
        ResponseTimeListener responseTimeListener = new ResponseTimeListener();
        env.put(ResponseTimeListener.class.getName(), responseTimeListener);
        ResponseStatusListener responseStatusListener = new ResponseStatusListener();
        env.put(ResponseStatusListener.class.getName(), responseStatusListener);

        LoadGenerator generator = LoadGenerator.builder()
                .scheme("http")
                .host(SERVER_IP)
                .port(PORT)
                .runFor(WARMUP_DURATION.plus(RUN_DURATION).toSeconds(), TimeUnit.SECONDS)
                .threads(2)
                .rateRampUpPeriod(WARMUP_DURATION.toSeconds() / 2)
                .resourceRate(RPS_LOADER)
                .resource(new Resource("/"))
                .resourceListener(responseTimeListener)
                .listener(responseTimeListener)
                .resourceListener(responseStatusListener)
                .listener(responseStatusListener)
                .build();


        CompletableFuture<Void> cf = generator.begin();
        // complete.get(); //make sure the load generation is complete

        cf = cf.whenComplete((x, f) -> {
            if (f == null) {
                LOGGER.info("load generation complete");
            } else {
                LOGGER.info("load generation failure " + f);
            }
        });

        env.put(CompletableFuture.class.getName(), cf);
        env.put(LoadGenerator.Config.class.getName(), generator.getConfig());
    }


    public static void createHgrmHistogram(File hlogFile, OutputStream out) throws FileNotFoundException {
        try (HistogramLogReader reader = new HistogramLogReader(hlogFile)) {
            Histogram total = new Histogram(3);
            while (reader.hasNext()) {
                Histogram histogram = (Histogram) reader.nextIntervalHistogram();
                total.add(histogram);
            }
            PrintStream ps = new PrintStream(out);
            total.outputPercentileDistribution(ps, 1000000.00); // scale by 1000000.00 to report in milliseconds
            ps.flush();
        }
    }


    public static boolean assertThroughput(Path reportRootPath, NodeArrayConfiguration nodeArray, long expectedValue, double errorMargin) throws FileNotFoundException
    {
        long totalCount = 0L;
        for (Node node : nodeArray.nodes())
        {
            Path perfHlog = reportRootPath.resolve(node.getId()).resolve("perf.hlog");
            try (HistogramLogReader histogramLogReader = new HistogramLogReader(perfHlog.toFile()))
            {
                while (true)
                {
                    AbstractHistogram histogram = (AbstractHistogram)histogramLogReader.nextIntervalHistogram();
                    if (histogram == null)
                        break;

                    totalCount += histogram.getTotalCount();
                }
            }
        }

        System.out.println("  " + nodeArray.id() + " throughput is " + totalCount + " vs expected " + expectedValue);
        double error = expectedValue * errorMargin / 100.0;
        double highBound = expectedValue + error;
        double lowBound = expectedValue - error;
        if (totalCount >= lowBound && totalCount <= highBound)
        {
            System.out.println("  OK; value within " + errorMargin + "% error margin");
            return true;
        }
        else
        {
            System.out.println("  NOK; value out of " + errorMargin + "% error margin");
            return false;
        }
    }



    private static EnumSet<ConfigurableMonitor.Item> copy(EnumSet<ConfigurableMonitor.Item> base, ConfigurableMonitor.Item... items) {
        EnumSet<ConfigurableMonitor.Item> result = EnumSet.copyOf(base);
        result.addAll(Arrays.asList(items));
        return result;
    }


}
