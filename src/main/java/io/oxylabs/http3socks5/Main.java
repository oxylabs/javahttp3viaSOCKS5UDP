package io.oxylabs.http3socks5;

import picocli.CommandLine;
import picocli.CommandLine.Option;

import java.util.concurrent.Callable;

@CommandLine.Command(
        name = "http3viasocks5udp",
        version = "1.0.0",
        description = "Perform an HTTP/3 GET request through a SOCKS5 UDP proxy.")
public final class Main implements Callable<Integer> {

    @Option(names = {"--help"}, usageHelp = true, description = "show this help message and exit")
    boolean help;

    @Option(names = "-u", description = "proxy username", defaultValue = "")
    String proxyUsername;

    @Option(names = "-p", description = "proxy password", defaultValue = "")
    String proxyPassword;

    @Option(names = "-h", description = "proxy host with port, i.e. host:port",
            defaultValue = "socks.pr.oxylabs.io:7777")
    String proxyHost;

    @Option(names = "-t", description = "target host (optionally host:port, default port 443)",
            defaultValue = "echo-udp.oxylabs.io")
    String targetHost;

    @Option(names = "-body", description = "optional request body", defaultValue = "")
    String payload;

    @Override
    public Integer call() {
        long start = System.currentTimeMillis();
        int rc = 0;
        try {
            HostPort proxy = HostPort.parse(proxyHost, 1080);
            HostPort target = HostPort.parse(targetHost, 443);
            new Http3ViaSocks5Client(proxy.host, proxy.port, proxyUsername, proxyPassword)
                    .execute(target.host, target.port, payload);
        } catch (Exception ex) {
            System.err.println("failed to perform HTTP3 request via proxy: " + ex.getMessage());
            ex.printStackTrace();
            rc = 1;
        }
        System.out.printf("Request took: %d ms%n", System.currentTimeMillis() - start);
        return rc;
    }

    public static void main(String[] args) {
        int code = new CommandLine(new Main()).execute(args);
        System.exit(code);
    }

    private static final class HostPort {
        final String host;
        final int port;

        private HostPort(String host, int port) {
            this.host = host;
            this.port = port;
        }

        static HostPort parse(String spec, int defaultPort) {
            int i = spec.lastIndexOf(':');
            if (i < 0) {
                return new HostPort(spec, defaultPort);
            }
            return new HostPort(spec.substring(0, i), Integer.parseInt(spec.substring(i + 1)));
        }
    }
}
