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

public class PingPongTask extends Task {
   
   private double timeVal;
   private boolean isPing = true;
   
   public PingPongTask() throws NativeException {
      this.timeVal = 0;
   }
   
   public PingPongTask(String name, double computeDuration, double messageSize) throws NativeException {      
      super(name,computeDuration,messageSize);		
   }
   
   public void setTime(double timeVal){
      this.timeVal = timeVal;
   }
   
   public double getTime() {
      return this.timeVal;
   }

   public void setIsPing(boolean IsPing)
   {
	this.isPing = IsPing;
   }

   public boolean getIsPing()
   {
	return this.isPing;
   }

   public String getSenderName()
   {
	return this.getSource().getName();
   }

   public Comm processPing() throws MsgException
   {
	double msgSz = 50;
	double computeDuration = 0;

	// Send back Pong message.
	String sender = this.getSource().getName();
	// Msg.info(hostName + " receives a Ping message from " + sender);
	PingPongTask pongTask = new PingPongTask("Pong", computeDuration, msgSz);
	pongTask.setIsPing(false);
	pongTask.setTime(this.getTime());
	Comm comm = pongTask.isend(sender);

	// this.comms.add(comm);
	// Msg.info(hostName + " sends a Pong message to " + sender);
	return comm;
   }

   public double processPong() throws MsgException
   {
	double RTT = 0;
	String sender = this.getSenderName();
	// Msg.info(hostName + " receives a Pong message from " + sender);

        double timeGot = Msg.getClock();
        double timeSent = this.getTime();
        RTT = timeGot - timeSent;
        // Msg.info("[RTT]-->" + this.getSource().getName() + " " + RTT + " ms!");
        // System.out.println(this.hostName + "-->" + sender + ", " + RTT);
        return RTT;
   }
} 
