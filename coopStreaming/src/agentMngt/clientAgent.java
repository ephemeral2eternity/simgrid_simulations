package agentMngt;

import java.util.*;
import java.lang.*;
import java.lang.reflect.Array;
import java.io.*;
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
	private ArrayList<String> qoeHeader;
	private Map<String, Integer> serverLevels;
	private Map<String, Integer> qoeCount;
	private Map<String, Double> serverQoE;
	private QoEComparator qoeCmp;
	private PrintWriter rstFile;
	private PrintWriter qoeFile;
	private double[] curCoords = {0.0, 0.0};
	private double buf;
	private int curSeq;
	private double startTime;
	private double playTime;
	private double freezeTime;
	static private double[] bitrates = {400.0, 628.0, 986.0, 1549.0, 2433.0, 3821.0, 6000.0};
	static private double CHUNKLEN = 5.0;
	static private int VIDLEN = 120;
	
	public clientAgent(Host host, String name, String[] args) {
		super(host, name, args);
		this.clientName = host.getName();
		this.comms = new ArrayList<Comm>();
		this.serverLevels = new HashMap<String, Integer>();
		this.serverQoE = new HashMap<String, Double>();
		this.videoServers = new ArrayList<String>();
		this.qoeHeader = new ArrayList<String>();
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

	public int findNextLevel(double bw, double buf)
	{
		int nextLv = 1;
		int i = 1;
		int maxLvl = this.bitrates.length - 1;
		for (double rate : this.bitrates)
		{
			if (bw > rate) {
				nextLv = i;
			}
			i ++;
		}
		if (this.buf > CHUNKLEN * 4)
		{
			nextLv = ((nextLv + 1) > maxLvl)?maxLvl : (nextLv + 1);
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
		
		// Write sorted qoes
		String sortedQoE = "";
		String selectedServer = curServer;

		String topServer = this.videoServers.get(0);
		double topQoE = this.serverQoE.get(topServer);
		double relaxation = 0.2;
		double window = 12;

		// Add probablistic switching
		// double denominator = (double) Math.max(this.curSeq + 1, 10);
		// double p = this.qoeCount.get(curServer) / denominator;

		// Change probability function of switching
		double p;
		if (nextQoE + relaxation > topQoE)
			p = 1.0;
		else if ((nextQoE > 4.0) && (this.qoeCount.get(curServer) == 0))
			p = 0.5;
		else {
			// p = 1 - Math.pow(0.5, this.qoeCount.get(curServer));
			double denominator = (double) Math.max(this.curSeq + 1, window);
			p = this.qoeCount.get(curServer) / denominator;
		}
		double d = Math.random();

		if ((topQoE > nextQoE) && (d > p))
		{
			selectedServer = topServer;
		}

		// If client switches servers, the goodCount is clear to 0 for the previous server.
		// if (!selectedServer.equals(curServer))
		// 	this.qoeCount.put(curServer, 0);

		return selectedServer;
	}

	public double computeQoE(double freezingTime, int bitrateLevel)
	{
		// double delta = 0.5;
		double delta = 0.2;
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

	public void updateQoE(String server, double qoe, boolean cooperation)
	{
		double alpha = 0.5;   // A higher alpha discounts older observations faster.
		double preQoE = this.serverQoE.get(server);
		double newQoE = qoe * alpha + (1 - alpha) * preQoE;
		this.serverQoE.put(server, newQoE);

		// Send QoE to the cache agent.
		if (cooperation) {
			try {
				// Comm updateComm = QoETask.sendQoEList(this.cacheAgent, this.serverQoE);
				Comm updateComm = QoETask.sendQoEUpdate(this.cacheAgent, server, qoe);
				this.comms.add(updateComm);
			} catch (MsgException e) {
				Msg.info("Update message sent failure: " + e.toString());
			}
		}
		this.writeQoE();
	}

	public void writeQoE()
	{
		String qoes = "";
		for (int i = 0; i < this.qoeHeader.size() - 1; i++) {
			String server = this.qoeHeader.get(i);
			String curQoE = String.format("%.2f", this.serverQoE.get(server));
			qoes = qoes + curQoE + "\t";
		}
		String curQoE = String.format("%.2f", this.serverQoE.get(this.qoeHeader.get(this.qoeHeader.size() - 1)));
		qoes = qoes + curQoE + "\n";
		this.qoeFile.write(qoes);
                // this.qoeFile.println(qoes);
                // this.qoeFile.flush();
	}

	public void updateQoECount(String server, double qoe)
	{
		double qoeHighTh = 4.0;
		double qoeLowTh = 2.0;
		int goodQoECount = this.qoeCount.get(server);
		
		if (qoe > qoeHighTh)
			goodQoECount ++;
		else if (qoe < qoeLowTh)
			goodQoECount = 0;
		
		this.qoeCount.put(server, goodQoECount);
	}

	public void syncQoE(Task recvTask) 
	{
		String qoePair = "";
		QoETask recvSyncQoE = (QoETask) recvTask;
		Map<String, Double> rcvQoEMap = recvSyncQoE.getQoEList();
		Iterator it = rcvQoEMap.entrySet().iterator();
		while (it.hasNext())
		{
			Map.Entry<String, Double> pair = (Map.Entry<String, Double>) it.next();
			if (this.videoServers.contains(pair.getKey()))
			{
				this.serverQoE.put(pair.getKey(), pair.getValue());
			}
		}
	}

	public void processResponse(Task recvTask) throws HostFailureException, MsgException {
		double curTime;
		double msgSz;
		double dTime, bw;
		double curQoE, curBuf, curPlayTime, nextQoE;
		double curFreezing = 0, nextFreezing = 0;
		String curServer, nextServer;
		int curLevel, nextLevel;
		int seq;
		int lvls = this.bitrates.length;
		boolean cooperate = false;
		boolean qoeDriven = true;
		// boolean qoeDriven = false;

		StreamingTask recvSTask = (StreamingTask) recvTask;
		if (!recvSTask.getIsRequest())
		{
			curTime = Msg.getClock();
			if (recvSTask.getNum() == 0)
			{
				this.startTime = curTime;
			}
			curPlayTime = curTime - this.startTime - this.freezeTime;
			seq = recvSTask.getNum();
			curBuf = recvSTask.getChunkLen() * (seq + 1) - curPlayTime;
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
				this.playTime = (seq + 1) * CHUNKLEN;
				nextLevel = 1;
			}

			// Predict user Quality of Experience.
			bw = (msgSz / dTime) / 1024;
			curQoE = computeQoE(curFreezing, curLevel);

			// Update counts of good QoE with the current server;
			nextLevel = findNextLevel(bw, this.buf);
			nextServer = curServer;

			if (qoeDriven)
			{
				updateQoECount(curServer, curQoE);
				if (seq % 10  == 0)
					cooperate = true;
				updateQoE(curServer, curQoE, cooperate);

				// Find next best server to serve next chunk.
				nextFreezing = findNextFreeze(bw, this.buf, nextLevel);
				nextQoE = computeQoE(nextFreezing, nextLevel);
				nextServer = findNextServer(curServer, nextQoE);

				// If the client should switch server, get the level of bitrate as the level you get from last time.
				// if (!nextServer.equals(curServer))
				//	nextLevel = nextLevel + 1;
					// nextLevel = this.serverLevels.get(nextServer);
			}

			if (this.buf > CHUNKLEN * 6) {
				waitFor(CHUNKLEN);
			}
			else if ((this.buf < CHUNKLEN * 0.5) && (this.buf > 0)) {
				nextLevel = 1;
			}

			Msg.info("Played: " + this.playTime + "; Current Time: " + curTime +"; Seq: " + recvSTask.getNum() + "; Server: " + curServer +  "; QoE: " + curQoE + "; BW: " + bw + " kbps; Total Freeze: " + this.freezeTime + "; Level: " + curLevel);
			// System.out.println(recvSTask.getNum() + ", " + curServer + ", "+ curQoE + ", " + bw);
			String outLn = Integer.toString(seq) + ", " + String.format("%.2f", curTime) + ", " + curServer + ", " + String.format("%.2f", curQoE) + ", " + String.format("%.2f", bw) + ", " + String.format("%.2f", this.freezeTime) + ", " + Integer.toString(curLevel) + "\n";
			// this.rstFile.println(recvSTask.getNum() + ", " + curTime + ", " + curServer + ", "+ curQoE + ", " + bw + ", " + this.freezeTime + ", " + curLevel);
			// this.rstFile.flush();
			this.rstFile.write(outLn);
			this.curSeq = seq + 1;
			if (this.curSeq < VIDLEN)
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
		// double w = - Math.log(u) / lambda;
		double w = u / lambda;

		return w;
	}

	public void parseArgs(String[] args) {
		int inputArgs = args.length;
		int candidateNum = 5;
		if (inputArgs > 0)
		{
			try {
				this.cacheAgent = Host.getByName(args[0]).getName();
				this.videoServers.add(this.cacheAgent);
				this.qoeHeader.add(this.cacheAgent);
				this.serverLevels.put(this.cacheAgent, 2);
				this.serverQoE.put(this.cacheAgent, 5.0);
				this.qoeCount.put(this.cacheAgent, 0);
				this.rstFile = new PrintWriter("./data/" + this.clientName + "_rst.csv");
				this.qoeFile = new PrintWriter("./data/" + this.clientName + "_qoe.csv");
				for (int i = 1; i < Math.min(inputArgs, candidateNum); i ++)
				{
					String server = Host.getByName(args[i]).getName();
					this.qoeHeader.add(server);
					this.videoServers.add(server);
					this.serverLevels.put(server, 1);
					if (server.equals("Server_0"))
						this.serverQoE.put(server, 4.0);
					else {
						this.serverQoE.put(server, Math.random() * 1.0 + 3);
					}
					this.qoeCount.put(server, 0);
				}
				String qoeHeaderStr = "";
				for (int i = 0; i < this.qoeHeader.size() - 1; i++) {
					qoeHeaderStr = qoeHeaderStr + this.qoeHeader.get(i) + "\t";
				}
				qoeHeaderStr = qoeHeaderStr + this.qoeHeader.get(this.qoeHeader.size() - 1) + "\n";
				this.qoeFile.write(qoeHeaderStr);
 
			} catch (HostNotFoundException | IOException e) {
				Msg.info("Invalid deployment file OR Unable to create result file ");
				System.exit(1);
			}
		}
	}

	public void main(String[] args) throws MsgException {
		Task recvTask = null;
		int timeoutCnt = 0;
		Random rd = new Random();
		int randomInt = rd.nextInt(3) + 1;
		// int randomInt = rd.nextInt(3);
		double lambda = 1 / Math.pow(16.0, randomInt);
		// double lambda = 1 / Math.pow(8.0, randomInt);
		// int vidNum = 2;
	
		// Process input arguments
		this.parseArgs(args);

		// for (int v = 0; v < vidNum; v ++)
		// {
		// Waitfor the next request of a video. 
		double waitTime = getWaitTime(lambda);
		waitFor(waitTime);
		
		// Msg.info(printMap(this.serverLevels));
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
			// if ((this.comms.size() == 0) && (this.curSeq >= 720))
				break;
		}
		//}	

		Msg.info("goodbye!");
		this.rstFile.flush();
		this.rstFile.close();
		this.qoeFile.flush();
		this.qoeFile.close();
	}
}
