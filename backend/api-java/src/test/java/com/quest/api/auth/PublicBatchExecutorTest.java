package com.quest.api.auth;

import com.quest.api.publicbank.PublicBatchExecutor;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;

class PublicBatchExecutorTest {
    @Test void onlyExplicitBatchRunsAfterTransitionAndNeverRetries() {
        List<Runnable> queue=new ArrayList<>();List<UUID> processed=new ArrayList<>();
        var executor=new PublicBatchExecutor(queue::add,processed::add);
        UUID id=UUID.randomUUID();
        assertThat(executor.start(id,()->"queued")).isEqualTo("queued");
        assertThat(processed).isEmpty();
        queue.removeFirst().run();
        assertThat(processed).containsExactly(id);
    }
    @Test void rejectedCapacityCannotChangeDocumentState() {
        var transitions=new AtomicInteger();
        var executor=new PublicBatchExecutor(task->{throw new RejectedExecutionException();},id->{throw new AssertionError();});
        assertThatThrownBy(()->executor.start(UUID.randomUUID(),transitions::incrementAndGet)).isInstanceOf(UserFailure.class);
        assertThat(transitions.get()).isZero();
    }
    @Test void rejectedTransitionsReturnQueueCapacityWhileAnotherBatchIsBusy() throws Exception {
        var pool=new java.util.concurrent.ThreadPoolExecutor(1,1,0,java.util.concurrent.TimeUnit.SECONDS,new java.util.concurrent.ArrayBlockingQueue<>(8));
        var entered=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);
        try {
            var executor=new PublicBatchExecutor(pool,id->{entered.countDown();try{release.await();}catch(InterruptedException e){Thread.currentThread().interrupt();}});
            executor.start(UUID.randomUUID(),()->"queued");
            assertThat(entered.await(2,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            for(int i=0;i<8;i++) assertThatThrownBy(()->executor.start(UUID.randomUUID(),()->{throw new UserFailure(409,40920,"Already started");})).isInstanceOf(UserFailure.class);
            assertThat(pool.getQueue()).isEmpty();
            assertThat(executor.start(UUID.randomUUID(),()->"queued")).isEqualTo("queued");
        } finally {release.countDown();pool.shutdownNow();}
    }
    @Test void deniedOrDuplicateStartDoesNotCallProvider() {
        List<Runnable> queue=new ArrayList<>();var calls=new AtomicInteger();
        var executor=new PublicBatchExecutor(queue::add,id->calls.incrementAndGet());
        assertThatThrownBy(()->executor.start(UUID.randomUUID(),()->{throw UserFailure.denied();})).isInstanceOf(UserFailure.class);
        queue.removeFirst().run();assertThat(calls.get()).isZero();
    }
}
