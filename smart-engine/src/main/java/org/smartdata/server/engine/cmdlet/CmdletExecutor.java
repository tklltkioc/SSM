/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.smartdata.server.engine.cmdlet;

import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import org.smartdata.actions.SmartAction;
import org.smartdata.common.CmdletState;
import org.smartdata.common.message.ActionStatus;
import org.smartdata.common.message.StatusReporter;
import org.smartdata.common.message.ActionStatusReport;
import org.smartdata.common.message.CmdletStatusUpdate;

import javax.annotation.Nullable;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

//Todo: 1. make this a interface so that we could have different executor implementation
//      2. add api providing available resource
public class CmdletExecutor {
  private final StatusReporter reporter;
  private Map<Long, Future> listenableFutures;
  private Map<Long, Cmdlet> runningCmdlets;
  private ListeningExecutorService executorService;

  public CmdletExecutor(StatusReporter reporter) {
    this.reporter = reporter;
    this.listenableFutures = new ConcurrentHashMap<>();
    this.runningCmdlets = new ConcurrentHashMap<>();
    this.executorService = MoreExecutors.listeningDecorator(Executors.newFixedThreadPool(10));
  }

  public void execute(Cmdlet cmdlet) {
    cmdlet.setState(CmdletState.EXECUTING);
    this.reportCurrentStatus(cmdlet);
    ListenableFuture<?> future = this.executorService.submit(cmdlet);
    Futures.addCallback(future, new CmdletCallBack(cmdlet), executorService);
    this.listenableFutures.put(cmdlet.getId(), future);
    this.runningCmdlets.put(cmdlet.getId(), cmdlet);
  }

  public void stop(Long cmdletId) {
    if (this.listenableFutures.containsKey(cmdletId)) {
      this.listenableFutures.get(cmdletId).cancel(true);
    }
    removeCmdlet(cmdletId);
  }

  public void shutdown() {
    this.executorService.shutdown();
  }

  public ActionStatusReport getActionStatusReport() {
    List<ActionStatus> actionStatusList = new ArrayList<>();
    for (Cmdlet cmdlet : this.runningCmdlets.values()) {
      for (SmartAction action : cmdlet.getActions()) {
        try {
          actionStatusList.add(action.getActionStatus());
        } catch (UnsupportedEncodingException e) {
          e.printStackTrace();
        }
      }
    }
    return new ActionStatusReport(actionStatusList);
  }

  private void reportCurrentStatus(Cmdlet cmdlet) {
    this.reporter.report(
        new CmdletStatusUpdate(cmdlet.getId(), System.currentTimeMillis(), cmdlet.getState()));
  }

  private void removeCmdlet(long cmdletId) {
    this.runningCmdlets.remove(cmdletId);
    this.listenableFutures.remove(cmdletId);
  }

  private class CmdletCallBack implements FutureCallback<Object> {
    private final Cmdlet cmdlet;

    public CmdletCallBack(Cmdlet cmdlet) {
      this.cmdlet = cmdlet;
    }

    @Override
    public void onSuccess(@Nullable Object result) {
      removeCmdlet(this.cmdlet.getId());
      reportCurrentStatus(this.cmdlet);
    }

    @Override
    public void onFailure(Throwable t) {
      removeCmdlet(this.cmdlet.getId());
      reportCurrentStatus(this.cmdlet);
    }
  }
}
