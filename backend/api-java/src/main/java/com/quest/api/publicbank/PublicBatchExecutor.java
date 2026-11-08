package com.quest.api.publicbank;

import com.quest.api.auth.UserFailure;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.ObjectMapper;

import java.util.UUID;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** 只执行用户明确启动的批次；不在重启时扫描队列，不自动重试失败项。 */
@Component
public class PublicBatchExecutor {
    private final Executor executor;
    private final Consumer<UUID> process;
    private final Consumer<UUID> processText;
    private final ExecutorService owned;

    @Autowired
    public PublicBatchExecutor(ObjectProvider<JdbcTemplate> jdbc,
                               ObjectProvider<PlatformTransactionManager> managers,
                               PublicAiClient ai, ObjectMapper json) {
        var pool = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(8), runnable -> {
                Thread thread = new Thread(runnable, "public-pdf-batch");
                thread.setDaemon(true);
                return thread;
            }, new ThreadPoolExecutor.AbortPolicy());
        this.executor = pool;
        this.owned = pool;
        this.process = id -> {
            var processor = new PublicImportProcessor(jdbc.getObject(), managers.getObject(), ai, json);
            for (int count = 0; count < 100 && !Thread.currentThread().isInterrupted(); count++) {
                if (!processor.processDocumentOne(id)) return;
            }
        };
        this.processText = id -> new PublicImportProcessor(jdbc.getObject(), managers.getObject(), ai, json).processTaskOne(id);
    }

    public PublicBatchExecutor(Executor executor, Consumer<UUID> process) {
        this.executor = executor;
        this.process = process;
        this.processText = process;
        this.owned = null;
    }

    public <T> T start(UUID id, Supplier<T> transition) { return submit(transition,result->process.accept(id)); }
    public PublicBankService.Task startText(Supplier<PublicBankService.Task> transition) {
        return submit(transition,task->processText.accept(UUID.fromString(task.id())));
    }
    private <T> T submit(Supplier<T> transition,Consumer<T> work) {
        // 先预留有界执行容量，再提交数据库状态；事务失败不会执行模型。
        var admitted = new CompletableFuture<T>();
        Runnable task = () -> {
            try {
                T result=admitted.get();
                if(result!=null)work.accept(result);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } catch (ExecutionException | RuntimeException failure) {
                // 不打印任务原文、供应商正文或凭据；未知结果不自动重试。
                org.slf4j.LoggerFactory.getLogger(PublicBatchExecutor.class)
                    .warn("Public import stopped; manual inspection required");
            }
        };
        try {
            executor.execute(task);
        } catch (RejectedExecutionException unavailable) {
            throw new UserFailure(503, 50323, "批次处理繁忙，请稍后重试");
        }
        try {
            T result = transition.get();
            admitted.complete(result);
            return result;
        } catch (RuntimeException | Error failure) {
            admitted.complete(null);
            if (executor instanceof ThreadPoolExecutor pool) pool.remove(task);
            throw failure;
        }
    }

    @PreDestroy
    public void close() {
        if (owned != null) owned.shutdownNow();
    }
}
