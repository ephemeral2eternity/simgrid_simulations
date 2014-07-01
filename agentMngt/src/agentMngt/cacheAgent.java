package agentMngt;

import java.util.ArrayList;
import java.lang.reflect.Array;
import java.util.Map;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import org.simgrid.msg.Host;
import org.simgrid.msg.HostNotFoundException;
import org.simgrid.msg.Msg;
import org.simgrid.msg.Task;
import org.simgrid.msg.MsgException;
import org.simgrid.msg.TimeoutException;
import org.simgrid.msg.Process;
import org.simgrid.msg.Comm;

public class cacheAgent extends Process {

	private String hostName = "";
	final double commSizeBw = 10000000;
	private int pongRcv = 0;
	private ArrayList<Comm> comms;
	private ArrayList<String> peerAgents;
	private ArrayList<Double> peerDiffs;
	private double[] preCoords = {0.0, 0.0};
	private double[] curCoords = {0.0, 0.0};
	
	public cacheAgent(Host host, String name, String[] args) {
		super(host, name, args);
		this.hostName = host.getName();
		this.comms = new ArrayList<Comm>();
		this.peerAgents = new ArrayList<String>();
		this.peerDiffs = new ArrayList<Double>();
	}

	public Comm ping(String peerAgent) throws MsgException
	{
		double msgSz = 50;
		double computeDuration = 0;
		double time = Msg.getClock();
		VivaldiTask task = new VivaldiTask("Ping", computeDuration, msgSz);
		task.setTime(time);
		task.setIsPing(true);
		task.setVivaldi(this.curCoords);
		// task.setError(diff);
		Comm comm = task.isend(peerAgent);
		// Msg.info(hostName + " sent a Ping message to " + peerAgent);
		return comm;
	}

        void copyArray(double[] dst, double[] src) {
		int i, len;
		len = Array.getLength(src);
		if (len != Array.getLength(dst))
		{
			System.out.println("[copyArray Error] The dimension of two input arrays are not the same!");
		}
		else
		{
			for (i = 0; i < len; i ++)
			{
				dst[i] = src[i];
			}
		}
	}

	public boolean isConverge(ArrayList<Double> diffs, double threshold)
	{
		boolean converge = true;
		// System.out.print("The differences are: ");
		for (Double curDiff : diffs)
		{
			// System.out.print(curDiff + ", ");
			if (curDiff > threshold)
				converge = false;
		}
		// System.out.print("\n");
		return converge;
	}

	public void main(String[] args) throws MsgException {
		int inputArgs = args.length;
		Task recvTask = null;
		int timeoutCnt = 0;
		int index = 0;
		double c_c = 0.4;
		double RTT = 2;
		double delta = 0.01;
		double diff = 1;
		double peerDiff = 1;
		double th = 0.1;

		// Msg.info("I am agent " + this.hostName);
		if (inputArgs > 0)
		{
			String strArg = new String(args[0]);
			if (strArg.endsWith(".csv"))
			{
				try {
					List<String> peers = Files.readAllLines(Paths.get(args[0]), Charset.defaultCharset());
					this.peerAgents.addAll(peers);
					for (String peer : this.peerAgents) {
						// Msg.info(peer);
						this.peerDiffs.add(diff);
					}
				} catch (IOException e) {
					Msg.info("Invalid host file name: " + args[0]);
				}
			}
			else
			{
				for(int pos = 0; pos < inputArgs; pos++) {
		   	   		try {
						// Msg.info("Input hostname: " + args[pos]);
						this.peerAgents.add(Host.getByName(args[pos]).getName());
						this.peerDiffs.add(diff);
		   	   		} catch (HostNotFoundException e) {
						Msg.info("Invalid deployment file: " + e.toString());
						System.exit(1);
		   	   		}
				}
			}
		
			for (int pos = 0; pos < this.peerAgents.size(); pos ++) {
				// Msg.info("Ping Agent : " + peerAgents.get(pos));
				ping(peerAgents.get(pos));
			}
		}

		// System.out.println(this.hostName + ": (" + preCoords[0] + " ," + preCoords[1] + ")");
		while (true){
			recvTask = null;	
			for (int i = 0; i < this.comms.size(); i ++) {
				try {
					if (this.comms.get(i).test()) {
						this.comms.remove(i);
						i --;
					}
				} catch (Exception e) {
					Msg.info("[Error] Message sent failure!!");
					this.comms.remove(i);
					// e.printStackTrace();
				}
			}
		
			try {
				recvTask = Task.receive(this.hostName, 100);
			} catch (TimeoutException e) {
				// Msg.info("[Exception] Timeout exception in retrieving tasks!");
				timeoutCnt ++;
			}
	
			if (recvTask != null)
			{
				if (recvTask instanceof VivaldiTask)
				{
					VivaldiTask recvppTask = (VivaldiTask) recvTask;
					if (recvppTask.getIsPing())
					{
						this.comms.add(recvppTask.processPing(this.preCoords, diff));
					}
					else
					{
						index = peerAgents.indexOf(recvppTask.getSenderName());
						RTT = recvppTask.processPong();
						peerDiff = recvppTask.getError();
						delta = c_c * diff / (diff + peerDiff);
						copyArray(this.curCoords, recvppTask.updateVivaldi(preCoords, RTT,  delta));
						diff = VivaldiTask.getDistance(preCoords, curCoords);
						// peerDiffs.set(index, diff);

						copyArray(preCoords, curCoords);

						if (diff > th)
							ping(recvppTask.getSenderName());
					}
				}
			}

			// boolean converge = isConverge(this.peerDiffs, th);		
			if ((this.comms.size() == 0) && (diff <= th) && (timeoutCnt > 3))
				break;
		}	

		System.out.println(this.hostName + ", " + curCoords[0] + " ," + curCoords[1]);
		// Msg.info("goodbye!");
	}
}
