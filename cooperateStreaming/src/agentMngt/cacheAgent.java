package agentMngt;

import java.util.*;
import java.lang.*;
import java.lang.reflect.Array;
import java.util.Map;
import java.io.PrintWriter;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.Random;
import org.simgrid.msg.Host;
import org.simgrid.msg.HostNotFoundException;
import org.simgrid.msg.Msg;
import org.simgrid.msg.Task;
import org.simgrid.msg.MsgException;
import org.simgrid.msg.TimeoutException;
import org.simgrid.msg.Process;
import org.simgrid.msg.Comm;

public class cacheAgent extends Process {

	private ArrayList<Comm> comms;
	private String hostName;
	private double[] curCoords = {0.0, 0.0};
	private Map<String, Double> serverQoE;
	private PrintWriter trafficFile;
	private PrintWriter qoeFile;
	static private double[] bitrates = {400.0, 628.0, 986.0, 1549.0, 2433.0, 3821.0, 6000.0};
	static private double CHUNKLEN = 5.0;
	
	public cacheAgent(Host host, String name, String[] args) {
		super(host, name, args);
		this.hostName = host.getName();
		this.comms = new ArrayList<Comm>();
		this.serverQoE = new HashMap<String, Double>();
	}

	public Comm processRequest(Task request) throws MsgException
	{
		double msgSz = 0;
		double computeDuration = 0;
		StreamingTask recvRequest = (StreamingTask) request;
		int rcvLevel = recvRequest.getLevel();
		msgSz = bitrates[rcvLevel - 1] * 1024 * 5;
		double time = Msg.getClock();
		StreamingTask data = new StreamingTask("Data", computeDuration, msgSz);
		// data.setTime(recvRequest.getTime());
		data.setTime(time);
		data.setLevel(rcvLevel);
		// System.out.println("Server sent level: " + rcvLevel);
		data.setChunkLen(CHUNKLEN);
		data.setNum(recvRequest.getNum());
		data.setIsRequest(false);
		String clientName = recvRequest.getSenderName();
		Comm comm = data.isend(clientName);
		this.trafficFile.println(time + ", " + clientName + ", " + msgSz);
		return comm;
	}

	public void updateServerQoE(String upd_server, double upd_qoe)
	{
		double alpha = 0.2;
		double preQoE = this.serverQoE.get(upd_server);
		double newQoE = upd_qoe * alpha + (1 - alpha) * preQoE;
		double time = Msg.getClock();
		Msg.info("Previous value for server " + upd_server + " is " + preQoE + " and updated qoe value is " + upd_qoe);
		this.qoeFile.println(time + ", " + upd_server + ", " + newQoE);
		this.serverQoE.put(upd_server, newQoE);
	}

	public void main(String[] args) throws MsgException {
		int inputArgs = args.length;
		Task recvTask = null;
		int lvls = this.bitrates.length;
		int timeoutCnt = 0;
		
		try {
			this.trafficFile = new PrintWriter("./data/" + this.hostName + "_traffic.csv");
			this.qoeFile = new PrintWriter("./data/" + this.hostName + "_qoe.csv");
		} catch (IOException e) {
			Msg.info("Unable to create result files for server: " + this.hostName);
			System.exit(1);
		}
		if (inputArgs > 0)
		{
			try {
				this.serverQoE.put(this.hostName, 5.0);
				for (int i = 0; i < inputArgs; i ++)
				{
					String server = Host.getByName(args[i]).getName();
					this.serverQoE.put(server, 5.0);
					Msg.info("Put qoe = 5.0 to server " + server);
				}
			} catch (HostNotFoundException e) {
				Msg.info("Invalid input arguments for cacheAgent in deployment file: " + e.toString());
				System.exit(1);
			}
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
					Msg.info("[Error] Message sent failure!!");
					// this.comms.remove(i);
				}
			}
		
			try {
				recvTask = Task.receive(this.hostName, 1000);
			} catch (TimeoutException e) {
				// Msg.info("[Exception] Timeout exception in retrieving tasks!");
				timeoutCnt ++;
			}
	
			if (recvTask != null)
			{
				timeoutCnt = 0;
				if (recvTask instanceof StreamingTask)
				{
					this.comms.add(processRequest(recvTask));
				}
				else if (recvTask instanceof QoETask)
				{
					QoETask recvUpdate = (QoETask) recvTask;
					Map<String, Double> updateQoEmap = new HashMap<String, Double>(recvUpdate.getQoEList());
					// double update_qoe = recvUpdate.getUpdateQoE();
					// Msg.info("Received updating QoE for Server: " + update_server + " with qoe: " + update_qoe);
					Iterator it = updateQoEmap.entrySet().iterator();
					while (it.hasNext())
					{
						Map.Entry<String, Double> pair = (Map.Entry<String, Double>) it.next();
						this.updateServerQoE(pair.getKey(), pair.getValue());
					}
					try {
						Comm syncComm = QoETask.sendQoESync(recvUpdate.getSenderName(), this.serverQoE);
						this.comms.add(syncComm);
					} catch (MsgException e) {
						Msg.info("Sync QoE sent failure: " + e.toString());
					}
				}
			}

			// boolean converge = isConverge(this.peerDiffs, th);		
			if ((this.comms.size() == 0) && (timeoutCnt > 100))
				break;
		}	

		Msg.info("goodbye!");
	}
}