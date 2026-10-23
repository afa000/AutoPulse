package dev.autopulse;

import java.nio.file.*;
import org.springframework.boot.SpringApplication;

/** Child JVM solely for the disposable commit-before-projection crash rehearsal. */
public final class TripDemoSeedServer {
    private TripDemoSeedServer() {}
    public static void main(String[] args) throws Exception {
        if(java.util.Arrays.stream(args).noneMatch("--autopulse.isolated-demo=true"::equals))
            throw new IllegalArgumentException("Use the JUnit-owned TripMilestoneDemo launcher");
        var app=SpringApplication.run(AutoPulseApplication.class,args);
        Files.writeString(Path.of(app.getEnvironment().getRequiredProperty("autopulse.demo-ready")),
                app.getEnvironment().getRequiredProperty("local.server.port"));
    }
}
