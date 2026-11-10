package com.quest.api.coach;

import com.quest.api.auth.UserFailure;
import java.io.Closeable;

/** 一个轮次的取消句柄，同时关闭上游响应体并中断阻塞调用。 */
public final class CoachStreamControl implements AutoCloseable {
    private volatile boolean cancelled;
    private volatile Thread worker;
    private Closeable upstream;

    public synchronized void attachThread(){check();worker=Thread.currentThread();}
    public synchronized void detachThread(){if(worker==Thread.currentThread())worker=null;}
    public synchronized void attach(Closeable stream){
        if(cancelled){closeQuietly(stream);check();}
        upstream=stream;
    }
    public synchronized void detach(){upstream=null;}
    public void check(){if(cancelled||Thread.currentThread().isInterrupted())throw new UserFailure(503,50332,"本次生成已中断，未自动重试");}
    public boolean cancelled(){return cancelled;}
    @Override public synchronized void close(){
        cancelled=true;
        Thread current=worker;if(current!=null&&current!=Thread.currentThread())current.interrupt();
        closeQuietly(upstream);upstream=null;
    }
    private static void closeQuietly(Closeable stream){if(stream!=null)try{stream.close();}catch(java.io.IOException ignored){/* 取消时尽力释放 */}}
}
