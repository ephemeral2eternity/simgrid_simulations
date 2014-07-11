/* Copyright (c) 2006-2014. The SimGrid Team.
 * All rights reserved.                                                     */

/* This program is free software; you can redistribute it and/or modify it
 * under the terms of the license (GNU LGPL) which comes with this package. */

package agentMngt;

import java.util.*;
import java.lang.*;
import java.lang.reflect.Array;
import org.simgrid.msg.Msg;
import org.simgrid.msg.Comm;
import org.simgrid.msg.MsgException;
import org.simgrid.msg.NativeException;

public class QoETask extends PingPongTask {

   private String updateServer;
   private double updateQoE;
   private Map<String, Double> syncQoEList;
   private boolean isUpdate;
   
   public QoETask(String name, double computeDuration, double messageSize) throws NativeException {
      super(name,computeDuration,messageSize);
   }

   public void setUpdate(String update_server, double update_qoe)
   {
	this.updateServer = update_server;
	this.updateQoE = update_qoe;
   }

   public double getUpdateQoE()
   {
	return this.updateQoE;
   }

   public String getUpdateServer()
   {
	return this.updateServer;
   }

   public void setSyncQoE(Map<String, Double> qoeMap)
   {
	this.syncQoEList = new HashMap<String, Double>(qoeMap);
   }

   public Map<String, Double> getSyncQoE()
   {
	return this.syncQoEList;
   }

   public void setIsUpdate(boolean is_update)
   {
	this.isUpdate = is_update;
   }

   public boolean getIsUpdate()
   {
	return this.isUpdate;
   }

   public static Comm sendQoESync(String sender, Map<String, Double> qoeMap) throws MsgException
   {
        double msgSz = 0;
        double computeDuration = 0;

	double time = Msg.getClock();
        QoETask syncTask = new QoETask("QoE_Sync", computeDuration, msgSz);
        syncTask.setIsUpdate(false);
        syncTask.setTime(time);
	syncTask.setSyncQoE(qoeMap);
        Comm comm = syncTask.isend(sender);

        return comm;
   }

   public static Comm sendQoEUpdate(String cacheAgent, String update_server, double update_qoe) throws MsgException
   {
	double msgSz = 0;
        double computeDuration = 0;

        double time = Msg.getClock();
        QoETask updateTask = new QoETask("QoE_Update", computeDuration, msgSz);
        updateTask.setIsUpdate(true);
        updateTask.setTime(time);
        updateTask.setUpdate(update_server, update_qoe);
        Comm comm = updateTask.isend(cacheAgent);
        return comm;
   }
}
