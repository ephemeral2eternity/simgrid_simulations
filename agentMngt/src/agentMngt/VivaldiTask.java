/* Copyright (c) 2006-2014. The SimGrid Team.
 * All rights reserved.                                                     */

/* This program is free software; you can redistribute it and/or modify it
 * under the terms of the license (GNU LGPL) which comes with this package. */

package agentMngt;

import java.lang.Math;
import java.lang.reflect.Array;
import org.simgrid.msg.Msg;
import org.simgrid.msg.Comm;
import org.simgrid.msg.MsgException;
import org.simgrid.msg.NativeException;

public class VivaldiTask extends PingPongTask {

   private double[] vivaldiCoords = {0.0, 0.0};
   private double error = 1.0;
   
   public VivaldiTask(String name, double computeDuration, double messageSize) throws NativeException {
      super(name,computeDuration,messageSize);
   }

   public double[] getVivaldiCoords()
   {
        return vivaldiCoords;
   }

   public double getError()
   {
	return this.error;
   }

   public void setError(double val)
   {
	this.error = val;
   }

   public Comm processPing(double[] newCoords, double diff) throws MsgException
   {
        double msgSz = 5000;
        double computeDuration = 0;

	double time = Msg.getClock();
        String sender = this.getSenderName();
        VivaldiTask pongTask = new VivaldiTask("Pong", computeDuration, msgSz);
        pongTask.setIsPing(false);
        pongTask.setTime(this.getTime());
        // pongTask.setTime(time);
	pongTask.setSeq(this.getSeq());
	pongTask.setVivaldi(newCoords);
	pongTask.setError(diff);
        Comm comm = pongTask.isend(sender);

        return comm;
   }

   public double[] updateVivaldi(double[] localVivaldiCoords, double RTT,  double delta) throws MsgException
   {
	double dist = getDistance(localVivaldiCoords, this.vivaldiCoords);
        double orientation = getOrientation(localVivaldiCoords, this.vivaldiCoords);
	// double RTT = this.processPong();	
	double[] newCoords = {0.0, 0.0};
	double error = RTT - dist;
	// Msg.info("The error is : " + error + "; The distance is : " + dist + "; The orientation is : " + orientation);
	double deltaX = delta * error * Math.cos(orientation);
	double deltaY = delta * error * Math.sin(orientation);

	newCoords[0] = localVivaldiCoords[0] + deltaX;
	newCoords[1] = localVivaldiCoords[1] + deltaY;

	return newCoords;
   }

   public static double getDistance(double[] localCoords, double[] remoteCoords)
   {
	double dist = 0;

	if (Array.getLength(localCoords) == Array.getLength(remoteCoords))
	{
		for (int i = 0; i < Array.getLength(localCoords); i ++)
			dist += Math.pow((localCoords[i] - remoteCoords[i]), 2);
		dist = Math.sqrt(dist);
	}
	else
	{
		System.out.println("[VivaldiTask Error] The dimension of two input vivaldi coordinates are not the same!!!!");
	}
	return dist;
   }

   public static double getOrientation(double[] localCoords, double[] remoteCoords)
   {
	int i = 0;
	double deltaX, deltaY, tanValue, ori;

	deltaX = localCoords[0] - remoteCoords[0];
	deltaY = localCoords[1] - remoteCoords[1];

	if ((deltaY == 0) && (deltaX == 0))
	{
		ori = (Math.random() *2 - 1) * Math.PI;
		// Msg.info("Randomly generate orientation: " + ori);
	}
	else if (deltaX == 0)
	{
		if (deltaY > 0)
			ori = Math.PI / 2;
		else
			ori = - Math.PI/2;
	}
	else
	{
		tanValue = deltaY / deltaX;
		ori = Math.atan(tanValue);
		if ((deltaX < 0) && (ori > 0))
			ori = ori - Math.PI;
		else if ((deltaX < 0) && (ori < 0))
			ori = ori + Math.PI;
	}

	return ori;
   }

   public void setVivaldi(double[] coords)
   {
	int i, len;
	len = Array.getLength(coords);
	if (len != Array.getLength(this.vivaldiCoords))
	{
		System.out.println("[VivaldiTask Error] The input array has a wrong dimension to set the vivaldi coordinates!" );
	}
	else
	{
		for (i = 0; i < len; i ++)
		{
			this.vivaldiCoords[i] = coords[i];
		}
	}
   }
}
