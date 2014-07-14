package agentMngt;

import java.util.*;
import java.lang.*;
import java.lang.reflect.Array;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.File;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Paths;
import org.simgrid.msg.Host;
import org.simgrid.msg.HostNotFoundException;
import org.simgrid.msg.HostFailureException;
import org.simgrid.msg.Msg;
import org.simgrid.msg.Task;
import org.simgrid.msg.MsgException;
import org.simgrid.msg.TimeoutException;
import org.simgrid.msg.Process;
import org.simgrid.msg.Comm;

public class clientAgent extends Process {

	private String clientName;
	private ArrayList<Comm> comms;
	private String cacheAgent;
	private ArrayList<String> videoServers;
	private Map<String, Integer> serverLevels;
	private Map<String, Integer> qoeCount;
	private Map<String, Double> serverQoE;
	private QoEComparator qoeCmp;
	private PrintWriter rstFile;
	private double[] curCoords = {0.0, 0.0};
	private double buf;
	private int curSeq;
	private double startTime;
	private double playTime;
	private double freezeTime;
	static private double[] bitrates = {400.0, 628.0, 986.0, 1549.0, 2433.0, 3821.0, 6000.0};
	static private double CHUNKLEN = 5.0;
	
	public clientAgent(Host host, String name, String[] args) {
		super(host, name, args);
		this.clientName = host.getName();
		this.comms = new ArrayList<Comm>();
		this.serverLevels = new HashMap<String, Integer>();
		this.serverQoE = new HashMap<String, Double>();
		this.videoServers = new ArrayList<String>();
		this.qoeCount = new HashMap<String, Integer>();
		this.buf = 0.0;
		this.freezeTime = 0.0;
		this.curSeq = 0;
	}

	public void request(String cacheServer, int num, int level) throws MsgException
	{
		double msgSz = 0;
		double computeDuration = 0;
		double time = Msg.getClock();
		StreamingTask request = new StreamingTask("Request", computeDuration, msgSz);
		request.setTime(time);
		request.setLevel(level);
		request.setChunkLen(CHUNKLEN);
		request.setNum(num);
		request.setIsRequest(true);
		Comm comm = request.isend(cacheServer);
		this.comms.add(comm);
		// return comm;
	}

	public int findNextLevel(double bw)
	{
		int nextLv = 1;
		int i = 1;
		for (double rate : this.bitrates)
		{
			if (bw > rate) {
				nextLv = i;
			}
			i ++;
		}
		return nextLv;
	}

	public double findNextFreeze(double pBw, double buffer, int nextLevel)
	{
		double pDownloadTime = 0;
		double nextBitrate = this.bitrates[nextLevel - 1];
		pDownloadTime = nextBitrate * CHUNKLEN / pBw;

		double nextBuf = buffer + CHUNKLEN - pDownloadTime;
		double nextFreeze = 0;

		if (nextBuf < 0)
			nextFreeze = -nextBuf;

		return nextFreeze;
	}

	public String findNextServer(String curServer, double nextQoE)
	{	
		Collections.sort(this.videoServers, this.qoeCmp);
		String topServer = this.videoServers.get(0);
		double topQoE = this.serverQoE.get(topServer);

		// Add probablistic switching
		double denominator = (double) Math.max(this.curSeq + 1, 100);
		double p = this.qoeCount.get(curServer) / denominator;
		double d = Math.random();

		if ((topQoE > nextQoE) && (d > p))
		// if (topQoE > nextQoE)
			return topServer;
		else
			return curServer;
	}

	public double computeQoE(double freezingTime, int bitrateLevel)
	{
		double delta = 0.5;
		double q_freeze = 5.0, q_bitrate = 5.0;
		double maxBitrate = this.bitrates[this.bitrates.length - 1];
		double curBitrate = this.bitrates[bitrateLevel - 1];

		double[] c = {5.0, 6.3484, 4.4, 0.72134};
		double[] a = {1.3554, 40};

		if (freezingTime > 0)
			q_freeze = c[0] - c[1] / (1 + Math.pow((c[2]/freezingTime), c[3]));

		q_bitrate = a[0] * Math.log(a[1]*curBitrate/maxBitrate);

		double qoe = delta * q_freeze + (1 - delta) * q_bitrate;
		return qoe;
	}

	public void updateQoE(String server, double qoe)
	{
		double alpha = 0.5;   // A higher alpha discounts older observations faster.
		double preQoE = this.serverQoE.get(server);
		double newQoE = qoe * alpha + (1 - alpha) * preQoE;
		this.serverQoE.put(server, newQoE);

		// Send QoE to the cache agent.
		try {
			Comm updateComm = QoETask.sendQoEUpdate(this.cacheAgent, server, qoe);
			this.comms.add(updateComm);
		} catch (MsgException e) {
			Msg.info("Update message sent failure: " + e.toString());
		}
	}

	public void updateQoECount(String server, double qoe)
	{
		double qoeTh = 4.0;
		int goodQoECount = this.qoeCount.get(server);
		
		if (qoe > qoeTh)
			goodQoECount ++;
		
		this.qoeCount.put(server, goodQoECount);
		// this.rstFile.println("## current QoE: " + qoe + ", current server good QoE count for server" + server + ": " + goodQoECount);
	}

	public void syncQoE(Task recvTask) 
	{
		String qoePair = "";
		QoETask recvSyncQoE = (QoETask) recvTask;
		Map<String, Double> rcvQoEMap = recvSyncQoE.getSyncQoE();
		Iterator it = rcvQoEMap.entrySet().iterator();
		while (it.hasNext())
		{
			Map.Entry<String, Double> pair = (Map.Entry<String, Double>) it.next();
			this.serverQoE.put(pair.getKey(), pair.getValue());
			// qoePair = qoePair + pair.getKey() + " --> " + pair.getValue() + ", ";
		}
		// this.rstFile.println("## current QoEs: " + qoePair);
	}

	public void processResponse(Task recvTask) throws HostFailureException, MsgException {
		double curTime;
		double msgSz;
		double dTime, bw;
		double curQoE, curBuf, curPlayTime, nextQoE;
		double curFreezing = 0, nextFreezing = 0;
		String curServer, nextServer;
		int curLevel, nextLevel;
		int lvls = this.bitrates.length;

		StreamingTask recvSTask = (StreamingTask) recvTask;
		if (!recvSTask.getIsRequest())
		{
			curTime = Msg.getClock();
			if (recvSTask.getNum() == 0)
			{
				this.startTime = curTime;
			}
			curPlayTime = curTime - this.startTime - this.freezeTime;
			curBuf = recvSTask.getChunkLen() * (recvSTask.getNum() + 1) - curPlayTime;
			curServer = recvSTask.getSenderName();
			msgSz = recvSTask.getMessageSize();
			dTime = (curTime - recvSTask.getTime());
			curLevel = recvSTask.getLevel();
			this.serverLevels.put(curServer, curLevel);

			if (curBuf >= 0) {
				curFreezing = 0;
				this.buf = curBuf;
				this.playTime = curPlayTime;
			}
			else {
				curFreezing = -curBuf;
				this.freezeTime += curFreezing;	
				this.buf = 0;
				this.playTime = (recvSTask.getNum() + 1) * CHUNKLEN;
				nextLevel = 1;
			}

			// Predict user Quality of Experience.
			bw = (msgSz / dTime) / 1024;
			nextLevel = findNextLevel(bw);
			nextFreezing = findNextFreeze(bw, this.buf, nextLevel);
			curQoE = computeQoE(curFreezing, curLevel);

			// Update counts of good QoE with the current server;
			updateQoECount(curServer, curQoE);
			
			nextQoE = computeQoE(nextFreezing, nextLevel);
			updateQoE(curServer, curQoE);

			// Find next best server to serve next chunk.
			nextServer = findNextServer(curServer, nextQoE);

			// If the client should switch server, get the level of bitrate as the level you get from last time.
			if (!nextServer.equals(curServer))
				nextLevel = this.serverLevels.get(nextServer);

			if (this.buf > CHUNKLEN * 6) {
				waitFor(CHUNKLEN);
			}
			else if ((this.buf < CHUNKLEN * 0.5) && (this.buf > 0)) {
				nextLevel = 1;
			}

			Msg.info("Played: " + this.playTime + "; Current Time: " + curTime +"; Seq: " + recvSTask.getNum() + "; Server: " + curServer +  "; QoE: " + curQoE + "; BW: " + bw + " kbps; Total Freeze: " + this.freezeTime + "; Level: " + curLevel);
			// System.out.println(recvSTask.getNum() + ", " + curServer + ", "+ curQoE + ", " + bw);
			this.rstFile.println(recvSTask.getNum() + ", " + curTime + ", " + curServer + ", "+ curQoE + ", " + bw + ", " + this.freezeTime + ", " + curLevel);
			this.curSeq = recvSTask.getNum() + 1;
			if (this.curSeq < 720)
			{
				// System.out.println("Client selected level: " + nextLevel);
				this.request(nextServer, this.curSeq, nextLevel);
			}
		}
		else
		{
			Msg.info("Wrongly sent video request to a client " + this.clientName);
		}
	}

	public String printMap(Map<String, Integer> levels) {
		String record = "";
		Iterator iter = levels.entrySet().iterator();
		while (iter.hasNext()) {
			Map.Entry<String, Integer> ent = (Map.Entry<String, Integer>) iter.next();
			record = record + ent.getKey() + ", " + ent.getValue() + "\n";
		}
		return record;
	}

	public double getWaitTime(double lambda)
	{
		double u = Math.random();
		double w = - Math.log(u) / lambda;

		return w;
	}

	public void parseArgs(String[] args) {
		int inputArgs = args.length;
		if (inputArgs > 0)
		{
			try {
				this.cacheAgent = Host.getByName(args[0]).getName();
				this.videoServers.add(this.cacheAgent);
				this.serverLevels.put(this.cacheAgent, 7);
				this.serverQoE.put(this.cacheAgent, 5.0);
				this.qoeCount.put(this.cacheAgent, 0);
				this.rstFile = new PrintWriter("./data/" + this.clientName + "_rst.csv");
				for (int i = 1; i < inputArgs; i ++)
				{
					String server = Host.getByName(args[i]).getName();
					this.videoServers.add(server);
					this.serverLevels.put(server, 6);
					this.serverQoE.put(server, 4.5);
					this.qoeCount.put(server, 0);
				}
			} catch (HostNotFoundException | IOException e) {
				Msg.info("Invalid deployment file OR Unable to create result file ");
				System.exit(1);
			}
		}
	}

	public void main(String[] args) throws MsgException {
		Task recvTask = null;
		int timeoutCnt = 0;
		double lambda = 1 / 100.0;
		double waitTime = getWaitTime(lambda);

		// Waitfor the next request of a video. 
		waitFor(waitTime);

		// Process input arguments
		this.parseArgs(args);
		
		Msg.info(printMap(this.serverLevels));
		this.qoeCmp = new QoEComparator(this.serverQoE);
		this.request(this.cacheAgent, this.curSeq, this.serverLevels.get(this.cacheAgent));

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
				}
			}
		
			try {
				recvTask = Task.receive(this.clientName, 1000);
			} catch (TimeoutException e) {
				Msg.info("[Exception] Timeout exception in retrieving tasks!");
				timeoutCnt ++;
			}
	
			if (recvTask != null)
			{
				timeoutCnt = 0;
				Msg.info("Data Received!");
				if (recvTask instanceof StreamingTask)
				{
					processResponse(recvTask);
				}
				else if (recvTask instanceof QoETask)
				{
					// Get new QoE values for all candidate servers.
					syncQoE(recvTask);
				}
			}

			// boolean converge = isConverge(this.peerDiffs, th);		
			if ((this.comms.size() == 0) && (timeoutCnt > 100))
				break;
		}	

		Msg.info("goodbye!");
		this.rstFile.close();
	}
}
