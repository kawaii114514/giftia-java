package com.giftia.config;

import java.io.IOException;
import java.nio.file.Files;
import javax.sql.DataSource;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
public class InfrastructureConfiguration {
    @Bean
    DataSource dataSource(GiftiaProperties properties) throws IOException {
        Files.createDirectories(properties.getDatabase().getParent());
        SQLiteConfig config = new SQLiteConfig();
        config.setBusyTimeout(10000);
        config.setJournalMode(SQLiteConfig.JournalMode.WAL);
        SQLiteDataSource source = new SQLiteDataSource(config);
        source.setUrl("jdbc:sqlite:" + properties.getDatabase());
        return source;
    }

    @Bean("analysisExecutor")
    ThreadPoolTaskExecutor analysisExecutor() { return executor("giftia-analysis-", 4, 8, 64); }
    @Bean("memoryExecutor")
    ThreadPoolTaskExecutor memoryExecutor() { return executor("giftia-memory-", 2, 2, 64); }

    private ThreadPoolTaskExecutor executor(String prefix, int core, int maximum, int capacity) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix(prefix);
        executor.setCorePoolSize(core);
        executor.setMaxPoolSize(maximum);
        executor.setQueueCapacity(capacity);
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(25);
        return executor;
    }
}
