/* Copyright (c) 2006-2014. The SimGrid Team.
 * All rights reserved.                                                     */

/* This program is free software; you can redistribute it and/or modify it
 * under the terms of the license (GNU LGPL) which comes with this package. */

package agentMngt;

import java.util.*;
import java.lang.*;
import java.util.Arrays;
import org.simgrid.msg.Msg;
import org.simgrid.msg.NativeException;
import org.simgrid.msg.Task;
import org.simgrid.msg.Comm;
import org.simgrid.msg.MsgException;

public class StreamingTask extends Task {
   
   private int chunkNum = 0;
   private double time = 0;
   private boolean isRequest = true;
   private double chunkLen = 0;
   private int level = 1;
   
   public StreamingTask(String name, double computeDuration, double messageSize) throws NativeException {      
      super(name,computeDuration,messageSize);		
   }
   
   public void setNum(int sqNum){
      this.chunkNum = sqNum;
   }
   
   public int getNum() {
      return this.chunkNum;
   }

   public void setTime(double t) {
	this.time = t;
   }

   public double getTime() {
	return this.time;
   }

   public double getChunkLen() {
	return this.chunkLen;
   }

   public void setChunkLen(double chLen) {
	this.chunkLen = chLen;
   }

   public void setIsRequest(boolean ISRequest)
   {
	this.isRequest = ISRequest;
   }

   public boolean getIsRequest()
   {
	return this.isRequest;
   }

   public String getSenderName()
   {
	return this.getSource().getName();
   }

   public int getLevel() {
	return this.level;
   }

   public void setLevel(int lvl) {
	this.level = lvl;
   }
} 
