package org.apache.solr.common;

import java.lang.invoke.MethodHandles;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.RunnableFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.solr.common.util.CloseTracker;
import org.apache.solr.common.util.ObjectReleaseTracker;
import org.apache.solr.common.util.SysStats;
import org.apache.solr.common.util.TimeOut;
import org.apache.solr.common.util.TimeSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PerThreadExecService extends AbstractExecutorService {
  private static final Logger log = LoggerFactory
      .getLogger(MethodHandles.lookup().lookupClass());

  private static final int MAX_AVAILABLE = Math.max(ParWork.PROC_COUNT / 2, 3);
  private final Semaphore available = new Semaphore(MAX_AVAILABLE, false);

  private final ExecutorService service;
  private final int maxSize;
  private final boolean noCallerRunsAllowed;
  private final boolean noCallerRunsAvailableLimit;
  private volatile boolean terminated;
  private volatile boolean shutdown;

  private final AtomicInteger running = new AtomicInteger();

  private CloseTracker closeTracker;

  private SysStats sysStats = ParWork.getSysStats();
  private volatile boolean closeLock;

  public PerThreadExecService(ExecutorService service) {
    this(service, -1);
  }

  public PerThreadExecService(ExecutorService service, int maxSize) {
    this(service, maxSize, false, false);
  }
  
  public PerThreadExecService(ExecutorService service, int maxSize, boolean noCallerRunsAllowed, boolean noCallerRunsAvailableLimit) {
    assert service != null;
    assert (closeTracker = new CloseTracker()) != null;
    this.noCallerRunsAllowed = noCallerRunsAllowed;
    this.noCallerRunsAvailableLimit = noCallerRunsAvailableLimit;
    if (maxSize == -1) {
      this.maxSize = MAX_AVAILABLE;
    } else {
      this.maxSize = maxSize;
    }
    this.service = service;
    running.incrementAndGet();
  }

  @Override
  protected <T> RunnableFuture<T> newTaskFor(Runnable runnable, T value) {
    if (noCallerRunsAllowed) {
      return (RunnableFuture) new ParWork.SolrFutureTask(runnable, value, false);
    }
    return (RunnableFuture) new ParWork.SolrFutureTask(runnable, value);
  }

  @Override
  protected <T> RunnableFuture<T> newTaskFor(Callable<T> callable) {
    if (noCallerRunsAllowed || callable instanceof ParWork.NoLimitsCallable) {
      return (RunnableFuture) new ParWork.SolrFutureTask(callable, false);
    }
    return (RunnableFuture) new ParWork.SolrFutureTask(callable, true);
  }

  @Override
  public void shutdown() {
//    if (closeLock) {
//      throw new IllegalCallerException();
//    }
   // assert closeTracker.close();
    assert ObjectReleaseTracker.release(this);
    this.shutdown = true;
    running.decrementAndGet();
    synchronized (running) {
      running.notifyAll();
    }
  }

  @Override
  public List<Runnable> shutdownNow() {
    shutdown = true;
    running.decrementAndGet();
    synchronized (running) {
      running.notifyAll();
    }
    return Collections.emptyList();
  }

  @Override
  public boolean isShutdown() {
    return shutdown;
  }

  @Override
  public boolean isTerminated() {
    return !available.hasQueuedThreads() && shutdown;
  }

  @Override
  public boolean awaitTermination(long l, TimeUnit timeUnit)
      throws InterruptedException {
    TimeOut timeout = new TimeOut(10, TimeUnit.SECONDS, TimeSource.NANO_TIME);
    synchronized (running) {

      while (running.get() > 0) {
        if (timeout.hasTimedOut()) {
          log.error("return before reaching termination, wait for {} {}, running={}", l, timeout, running);
          return false;
        }

        // System.out.println("WAIT : " + workQueue.size() + " " + available.getQueueLength() + " " + workQueue.toString());
        running.wait(1000);
      }
    }
    if (isShutdown()) {
      terminated = true;
    }
    return true;
  }


  @Override
  public void execute(Runnable runnable) {

//    if (shutdown) {
//      throw new RejectedExecutionException();
//    }

    running.incrementAndGet();
    if (runnable instanceof ParWork.SolrFutureTask && !((ParWork.SolrFutureTask) runnable).isCallerThreadAllowed()) {
      if (noCallerRunsAvailableLimit) {
        try {
          available.acquire();
        } catch (InterruptedException e) {
          ParWork.propagateInterrupt(e);
          running.decrementAndGet();
          synchronized (running) {
            running.notifyAll();
          }
          throw new RejectedExecutionException("Interrupted");
        }
      }
      try {
        service.submit(() -> {
          runIt(runnable, noCallerRunsAvailableLimit, false);
        });
      } catch (Exception e) {
        log.error("", e);
        if (noCallerRunsAvailableLimit) {
          available.release();
        }
        running.decrementAndGet();
        synchronized (running) {
          running.notifyAll();
        }
        throw e;
      }
      return;
    }

    boolean acquired = available.tryAcquire();
    if (!acquired && !noCallerRunsAllowed) {
      runIt(runnable, false, false);
      return;
    }

    Runnable finalRunnable = runnable;
    try {
      service.submit(() -> runIt(finalRunnable, true, false));
    } catch (Exception e) {
      log.error("Exception submitting", e);
      try {
        available.release();
      } finally {
        running.decrementAndGet();
        synchronized (running) {
          running.notifyAll();
        }
      }
      throw e;
    }
  }

  private void runIt(Runnable runnable, boolean acquired, boolean alreadyShutdown) {
    try {
      runnable.run();
    } finally {
      try {
        if (acquired) {
          available.release();
        }
      } finally {
        running.decrementAndGet();
        synchronized (running) {
          running.notifyAll();
        }
      }
    }
  }

  public Integer getMaximumPoolSize() {
    return maxSize;
  }

  private boolean checkLoad() {

    double sLoad = sysStats.getSystemLoad();

    if (hiStateLoad(sLoad)) {
      return true;
    }
    return false;
  }

  private boolean hiStateLoad(double sLoad) {
    return sLoad > 0.8d && running.get() > 3;
  }

  public void closeLock(boolean lock) {
    if (lock) {
      closeTracker.enableCloseLock();
    } else {
      closeTracker.disableCloseLock();
    }
  }

}
