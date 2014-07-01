/* Copyright (c) 2006-2014. The SimGrid Team.
 * All rights reserved.                                                     */

/* This program is free software; you can redistribute it and/or modify it
 * under the terms of the license (GNU LGPL) which comes with this package. */

package agentDiscovery;

import java.util.*;
import java.lang.*;
import org.simgrid.msg.Msg;
import org.simgrid.msg.NativeException;
import org.simgrid.msg.Task;
import org.simgrid.msg.Comm;
import org.simgrid.msg.MsgException;

public class DiscoverTask extends Task {
   
   private double timeVal;
   private Map<Integer, ArrayList<String>> data;
   
   public DiscoverTask(String name, double computeDuration, double messageSize) throws NativeException {
	super(name,computeDuration,messageSize);
	this.data = new HashMap<Integer, ArrayList<String>>();
	this.timeVal = 0;
   }
   
   public void setTime(double timeVal){
      this.timeVal = timeVal;
   }
   
   public double getTime() {
      return this.timeVal;
   }

   public String getSenderName()
   {
	return this.getSource().getName();
   }

   public void setData(Map<Integer, ArrayList<String>> updates)
   {
	Iterator iter = updates.entrySet().iterator();
	while (iter.hasNext())
	{
		Map.Entry<Integer, ArrayList<String>> record = (Map.Entry<Integer, ArrayList<String>>) iter.next();
		ArrayList<String> agentList = new ArrayList<String>();
		agentList.addAll(record.getValue());
		this.data.put(record.getKey(), agentList);
	}
   }

   public void copyData(Map<Integer, ArrayList<String>> dst)
   {
	Iterator iter = this.data.entrySet().iterator();
	while (iter.hasNext())
	{
		Map.Entry<Integer, ArrayList<String>> record = (Map.Entry<Integer, ArrayList<String>>) iter.next();
		ArrayList<String> agentList = new ArrayList<String>();
		agentList.addAll(record.getValue());
		dst.put(record.getKey(), agentList);
	}
   }

   public Map<Integer, ArrayList<String>> getData()
   {
	return this.data;
   }
} 
