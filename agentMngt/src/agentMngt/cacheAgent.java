package agentMngt;

import java.util.ArrayList;

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
	
	public cacheAgent(Host host, String name, String[] args) {
		super(host, name, args);
		this.hostName = host.getName();
		this.comms = new ArrayList<Comm>();
	}

	
	public void ping(String peerAgent) throws MsgException
	{
		double msgSz = 50;
		double computeDuration = 0;
		double time = Msg.getClock();
		PingPongTask task = new PingPongTask("Ping", computeDuration, msgSz);
		task.setTime(time);
		task.setIsPing(true);
		Comm comm = task.isend(peerAgent);
		this.comms.add(comm);
		Msg.info(hostName + " sent a Ping message to " + peerAgent);
	}

	public double processPing(PingPongTask revTask) throws MsgException
	{
		double msgSz = 50;
		double computeDuration = 0;
		double RTT = 0;
		if (revTask.getIsPing())
		{
			// Send back Pong message.
			String sender = revTask.getSource().getName();
			Msg.info(hostName + " receives a Ping message from " + sender);
			PingPongTask pongTask = new PingPongTask("Pong", computeDuration, msgSz);
			pongTask.setIsPing(false);
			pongTask.setTime(revTask.getTime());
			Comm comm = pongTask.isend(sender);
			this.comms.add(comm);
			Msg.info(hostName + " sends a Pong message to " + sender);
		}
		else
		{
			// Receive a Pong Message
			String sender = revTask.getSource().getName();
			Msg.info(hostName + " receives a Pong message from " + sender);
			
			double timeGot = Msg.getClock();
			double timeSent = revTask.getTime();
			RTT = timeGot - timeSent;
			this.pongRcv ++;
			Msg.info("[RTT]" + this.hostName + "<-->" + revTask.getSource().getName() + " " + RTT + " ms!");
			// System.out.println(this.hostName + "-->" + sender + ", " + RTT);
		}
		return RTT;
	}

	public void main(String[] args) throws MsgException {
		Msg.info("Hello, I am agent_" + this.hostName);

		int hostCount = args.length;
		Msg.info("# of Peer Agents: " + hostCount);
		Comm recvComm = Task.irecv(this.hostName);

		int iter = 0;
		int rcvCount = 0;
		Task recvTask = null;
		int timeoutCnt = 0;

		if (hostCount > 0)
		{
			String peerAgents[] = new String[hostCount];

			for(int pos = 0; pos < args.length; pos++) {
		   	   try {
				peerAgents[pos] = Host.getByName(args[pos]).getName();		
		   	   } catch (HostNotFoundException e) {
				Msg.info("Invalid deployment file: " + e.toString());
				System.exit(1);
		   	   }
			}
		
			for (int pos = 0; pos < hostCount; pos ++) {
				ping(peerAgents[pos]);
			}

			while (true){
				recvTask = null;	
				for (int i = 0; i < this.comms.size(); i ++) {
					try {
						if (this.comms.get(i).test()) {
							this.comms.remove(i);
							i --;
						}
					} catch (Exception e) {
						System.out.print("[Error] Message sent failure!!");
						e.printStackTrace();
					}
				}
				try {
					recvTask = Task.receive(this.hostName, 10);
				} catch (TimeoutException e) {
					Msg.info("[Exception] Timeout exception in retrieving tasks!");
					timeoutCnt ++;
				}
				if (recvTask != null)
				{
					if (recvTask instanceof agentMngt.PingPongTask)
					{
						processPing((PingPongTask) recvTask);
					}
				}
				if ((this.comms.size() == 0) && (timeoutCnt > 3))
					break;
			}	
		}
		Msg.info("goodbye!");
	}
}
