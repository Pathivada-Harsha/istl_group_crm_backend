package com.istlgroup.istl_group_crm_backend.config;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The thread pool the dashboard endpoints fan their queries out on.
 *
 * <p><b>Why this exists.</b> {@code DashboardService} used
 * {@code CompletableFuture.supplyAsync(...)} with no executor, which runs on
 * {@link java.util.concurrent.ForkJoinPool#commonPool()}. That is wrong here for
 * two separate reasons:
 *
 * <ul>
 *   <li><b>It is sized for CPU work, not waiting.</b> The common pool's
 *       parallelism is {@code availableProcessors() - 1}. Every dashboard task
 *       is a blocking JDBC call that spends ~all of its time waiting on the
 *       network, so the right width is "as many as the database will accept",
 *       not "as many as we have cores". On a 2-vCPU server the common pool is
 *       ONE thread — the admin dashboard's 22 "parallel" queries then run
 *       strictly one after another.</li>
 *   <li><b>It is shared with the whole JVM.</b> Parking common-pool threads on
 *       JDBC starves every parallel stream and every other
 *       {@code supplyAsync} in the application for the duration of the request.</li>
 * </ul>
 *
 * <p>Production talks to a REMOTE MySQL, so each query costs a network
 * round-trip. Serialising 22 of them is the difference between one round-trip of
 * latency and twenty-two of them — which is what "the dashboard takes a long
 * time to load" actually was.
 *
 * <p><b>Sizing.</b> Deliberately matched to
 * {@code spring.datasource.hikari.maximum-pool-size}. A thread here is useless
 * without a connection to go with it, so a wider pool would only move the
 * queueing from this executor into HikariCP and burn threads waiting. Keep the
 * two numbers in step if either changes.
 */
@Configuration
public class DashboardExecutorConfig {

    public static final String BEAN = "dashboardExecutor";

    @Bean(name = BEAN, destroyMethod = "shutdown")
    public Executor dashboardExecutor(
            @Value("${spring.datasource.hikari.maximum-pool-size:10}") int poolSize) {

        ThreadFactory tf = new ThreadFactory() {
            private final AtomicInteger n = new AtomicInteger(1);
            @Override public Thread newThread(Runnable r) {
                // Named so a thread dump says which pool is stuck on the database.
                Thread t = new Thread(r, "dashboard-q-" + n.getAndIncrement());
                t.setDaemon(true);
                return t;
            }
        };
        return Executors.newFixedThreadPool(Math.max(poolSize, 4), tf);
    }
}
